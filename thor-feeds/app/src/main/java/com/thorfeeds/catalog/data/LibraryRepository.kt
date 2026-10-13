package com.thorfeeds.catalog.data

import android.content.Context
import android.net.Uri
import com.thorfeeds.catalog.core.FeedChallengeException
import com.thorfeeds.catalog.core.FeedFormatException
import com.thorfeeds.catalog.core.FeedParser
import com.thorfeeds.catalog.core.FeedUrls
import com.thorfeeds.catalog.core.FeedWebReader
import com.thorfeeds.catalog.core.OnDeviceMatcher
import com.thorfeeds.catalog.core.UriHosts
import com.thorfeeds.catalog.net.FeedFetcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID

data class FeedSolveSession(
    val sourceId: String,
    val url: String,
    val displayId: Int,
)

class LibraryRepository(
    private val context: Context,
    private val settings: SettingsStore,
    private val dao: ReleaseDao,
    private val http: OkHttpClient,
    private val webFeeds: FeedWebReader,
    private val scanner: FolderScanner = FolderScanner(context),
    private val feeds: FeedFetcher = FeedFetcher(http),
) {
    val sources: Flow<List<SourceRecord>> = settings.sources
    val releases: Flow<List<ReleaseEntity>> = dao.observeAll()
    val folderUri: Flow<String?> = settings.folderUri
    val useSecondScreen: Flow<Boolean> = settings.useSecondScreen
    val deleteArchiveAfterExtract: Flow<Boolean> = settings.deleteArchiveAfterExtract
    val onDeviceKeys = MutableStateFlow<Set<String>>(emptySet())
    val refreshing = MutableStateFlow(false)
    val selection = MutableStateFlow<String?>(null)
    val folderLabel = MutableStateFlow<String?>(null)
    val folderProblem = MutableStateFlow<String?>(null)
    val solveSession = MutableStateFlow<FeedSolveSession?>(null)

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

    suspend fun setDeleteArchiveAfterExtract(enabled: Boolean) {
        settings.setDeleteArchiveAfterExtract(enabled)
    }

    suspend fun deleteArchiveAfterExtract(): Boolean = settings.deleteArchiveAfterExtract.first()

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
            val text = if (source.isRemote) readRemote(source.location) else withContext(Dispatchers.IO) { readLocal(source.location) }
            val feed = FeedParser.parse(text)
            dao.replaceFeed(source.id, feed.releases.map { it.toEntity(source.id) })
            updateRecord(source.copy(label = feed.name.ifBlank { source.label }, lastError = null, needsSolve = false))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: FeedChallengeException) {
            UriHosts.hostOf(source.location)?.let { host -> settings.forgetClearedHost(host) }
            updateRecord(source.copy(lastError = error.message, needsSolve = source.isRemote))
        } catch (error: Exception) {
            val message = when (error) {
                is FeedFormatException -> error.message
                else -> "Couldn't read this feed."
            }
            updateRecord(source.copy(lastError = message ?: "Couldn't read this feed.", needsSolve = false))
        }
    }

    fun beginSolve(sourceId: String, url: String, displayId: Int) {
        solveSession.value = FeedSolveSession(sourceId = sourceId, url = url, displayId = displayId)
    }

    fun clearSolve() {
        solveSession.value = null
    }

    suspend fun acceptWebFeed(sourceId: String, body: String) {
        val source = settings.sources.first().firstOrNull { it.id == sourceId } ?: return
        val feed = FeedParser.parse(body)
        dao.replaceFeed(source.id, feed.releases.map { it.toEntity(source.id) })
        UriHosts.hostOf(source.location)?.let { host -> settings.rememberClearedHost(host) }
        updateRecord(source.copy(label = feed.name.ifBlank { source.label }, lastError = null, needsSolve = false))
        rescan()
    }

    private suspend fun readRemote(url: String): String {
        if (solveSession.value?.url == url) {
            throw FeedChallengeException(
                "Finish Solve check on this feed. The check page is left open so it is not reloaded.",
            )
        }
        val host = UriHosts.hostOf(url)
        val cleared = host != null && host in settings.clearedFeedHosts.first()
        return if (cleared) webFeeds.load(url, interactive = false) else withContext(Dispatchers.IO) { feeds.fetch(url) }
    }

    private suspend fun updateRecord(record: SourceRecord) {
        val current = settings.sources.first()
        settings.setSources(current.map { if (it.id == record.id) record else it })
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
            if (total > FeedFetcher.MAX_FEED_BYTES) throw FeedFormatException("That file is too large.")
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

}
