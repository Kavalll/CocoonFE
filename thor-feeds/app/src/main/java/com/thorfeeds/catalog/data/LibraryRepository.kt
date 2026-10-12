package com.thorfeeds.catalog.data

import android.content.Context
import android.net.Uri
import com.thorfeeds.catalog.core.FeedFormatException
import com.thorfeeds.catalog.core.FeedParser
import com.thorfeeds.catalog.core.FeedUrls
import com.thorfeeds.catalog.core.OnDeviceMatcher
import com.thorfeeds.catalog.core.UriHosts
import com.thorfeeds.catalog.net.HttpIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID

class LibraryRepository(
    private val context: Context,
    private val settings: SettingsStore,
    private val dao: ReleaseDao,
    private val http: OkHttpClient,
    private val scanner: FolderScanner = FolderScanner(context),
) {
    val sources: Flow<List<SourceRecord>> = settings.sources
    val releases: Flow<List<ReleaseEntity>> = dao.observeAll()
    val folderUri: Flow<String?> = settings.folderUri
    val useSecondScreen: Flow<Boolean> = settings.useSecondScreen
    val onDeviceKeys = MutableStateFlow<Set<String>>(emptySet())
    val refreshing = MutableStateFlow(false)
    val selection = MutableStateFlow<String?>(null)
    val folderLabel = MutableStateFlow<String?>(null)
    val folderProblem = MutableStateFlow<String?>(null)

    fun select(canonicalTitle: String) {
        selection.value = canonicalTitle
    }

    suspend fun refreshAll() {
        val current = settings.sources.first()
        refreshing.value = true
        try {
            for (source in current) {
                refresh(source)
            }
        } finally {
            refreshing.value = false
            rescan()
        }
    }

    suspend fun addRemote(rawUrl: String) {
        val url = FeedUrls.normalize(rawUrl)
        val current = settings.sources.first()
        if (current.any { it.isRemote && it.location == url }) {
            throw IllegalArgumentException("That feed is already added")
        }
        val record = SourceRecord(
            id = UUID.randomUUID().toString(),
            kind = SourceRecord.KIND_REMOTE,
            location = url,
            label = UriHosts.hostOf(url) ?: "Feed",
        )
        settings.setSources(current + record)
        refresh(record)
        rescan()
    }

    suspend fun updateRemote(id: String, rawUrl: String) {
        val url = FeedUrls.normalize(rawUrl)
        val current = settings.sources.first()
        val existing = current.firstOrNull { it.id == id && it.isRemote }
            ?: throw IllegalArgumentException("That feed is not a URL")
        if (current.any { it.id != id && it.isRemote && it.location == url }) {
            throw IllegalArgumentException("That feed is already added")
        }
        val updated = existing.copy(location = url, lastError = null)
        settings.setSources(current.map { if (it.id == id) updated else it })
        refresh(updated)
        rescan()
    }

    suspend fun importLocal(uri: String, displayName: String) {
        val text = readLocal(uri)
        val feed = FeedParser.parse(text)
        val current = settings.sources.first()
        if (current.any { !it.isRemote && it.location == uri }) {
            throw IllegalArgumentException("That file is already imported")
        }
        val record = SourceRecord(
            id = UUID.randomUUID().toString(),
            kind = SourceRecord.KIND_LOCAL,
            location = uri,
            label = feed.name.ifBlank { displayName },
        )
        settings.setSources(current + record)
        dao.replaceFeed(record.id, feed.releases.map { it.toEntity(record.id) })
        rescan()
    }

    suspend fun remove(id: String) {
        val current = settings.sources.first()
        settings.setSources(current.filterNot { it.id == id })
        dao.deleteFeed(id)
        if (selection.value != null) {
            val stillThere = dao.canonicalTitles().any { it.equals(selection.value, ignoreCase = true) }
            if (!stillThere) selection.value = null
        }
        rescan()
    }

    suspend fun setFolder(uri: String) {
        settings.setFolderUri(uri)
        rescan()
    }

    suspend fun setUseSecondScreen(enabled: Boolean) {
        settings.setUseSecondScreen(enabled)
    }

    suspend fun rescan() {
        val uri = settings.folderUri.first()
        if (uri.isNullOrBlank()) {
            folderLabel.value = null
            folderProblem.value = null
            onDeviceKeys.value = emptySet()
            return
        }
        if (!scanner.hasAccess(uri)) {
            folderLabel.value = null
            folderProblem.value = "The games folder permission expired. Choose it again."
            onDeviceKeys.value = emptySet()
            return
        }
        folderProblem.value = null
        val entries = withContext(Dispatchers.IO) { scanner.scan(uri) }
        folderLabel.value = scanner.label(uri)
        val titles = dao.canonicalTitles()
        onDeviceKeys.value = OnDeviceMatcher.presentKeys(titles, entries)
    }

    suspend fun readRelease(id: String): ReleaseEntity? = dao.get(id)

    suspend fun currentFolder(): String? = settings.folderUri.first()

    private suspend fun refresh(source: SourceRecord) {
        try {
            val text = if (source.isRemote) readRemote(source.location) else readLocal(source.location)
            val feed = FeedParser.parse(text)
            dao.replaceFeed(source.id, feed.releases.map { it.toEntity(source.id) })
            updateRecord(source.copy(label = feed.name.ifBlank { source.label }, lastError = null))
        } catch (error: Exception) {
            val message = when (error) {
                is FeedFormatException -> error.message
                else -> "Couldn't read this feed."
            }
            updateRecord(source.copy(lastError = message ?: "Couldn't read this feed."))
        }
    }

    private suspend fun updateRecord(record: SourceRecord) {
        val current = settings.sources.first()
        settings.setSources(current.map { if (it.id == record.id) record else it })
    }

    private fun readRemote(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", HttpIdentity.USER_AGENT)
            .header("Accept", "application/json,text/plain,*/*")
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 404) throw FeedFormatException("The feed URL was not found.")
            if (!response.isSuccessful) throw FeedFormatException("The feed server returned ${response.code}.")
            val type = response.header("Content-Type").orEmpty()
            if (type.contains("html", ignoreCase = true)) {
                throw FeedFormatException("This is a web page, not a feed. Challenge pages are ignored.")
            }
            val length = response.body?.contentLength() ?: -1L
            if (length > MAX_FEED_BYTES) throw FeedFormatException("That feed is too large to import.")
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (bytes.size > MAX_FEED_BYTES) throw FeedFormatException("That feed is too large to import.")
            return bytes.toString(Charsets.UTF_8)
        }
    }

    private fun readLocal(uri: String): String {
        val stream = context.contentResolver.openInputStream(Uri.parse(uri))
            ?: throw FeedFormatException("Couldn't open that file.")
        stream.use { input ->
            return readLimited(input).toString(Charsets.UTF_8)
        }
    }

    private fun readLimited(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_FEED_BYTES) throw FeedFormatException("That file is too large.")
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    companion object {
        private const val MAX_FEED_BYTES = 48 * 1024 * 1024
    }
}
