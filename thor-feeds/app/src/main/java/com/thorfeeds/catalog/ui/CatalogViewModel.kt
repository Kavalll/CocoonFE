package com.thorfeeds.catalog.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.thorfeeds.catalog.ThorFeedsApp
import com.thorfeeds.catalog.core.ArtWindow
import com.thorfeeds.catalog.core.CatalogArt
import com.thorfeeds.catalog.core.CatalogSort
import com.thorfeeds.catalog.core.TitleCanonicalizer
import com.thorfeeds.catalog.download.DownloadRecord
import com.thorfeeds.catalog.download.DownloadService
import com.thorfeeds.catalog.download.ZArchiverLaunch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CatalogViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = ThorFeedsApp.graph(app)

    val releases = graph.library.releases.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val sources = graph.library.sources.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val folderUri = graph.library.folderUri.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val useSecondScreen = graph.library.useSecondScreen.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val deleteArchiveAfterExtract = graph.library.deleteArchiveAfterExtract.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val catalogSort = graph.library.catalogSort.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CatalogSort.NEWEST)
    val artByTitle = MutableStateFlow<Map<String, CatalogArt>>(emptyMap())
    private val artAttempted = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val artPublish = Any()
    private val artGate = Mutex()
    val onDeviceKeys = graph.library.onDeviceKeys
    val onDeviceArchives = graph.library.onDeviceArchives
    val refreshing = graph.library.refreshing
    val selection = graph.library.selection
    val folderLabel = graph.library.folderLabel
    val folderProblem = graph.library.folderProblem
    val download = graph.downloads.snapshot
    val downloadRecords = graph.downloads.records
    val solveSession = graph.library.solveSession
    val query = MutableStateFlow("")

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val cached = graph.steamArt.cached()
            publishArt(cached, overwrite = false)
            graph.library.restoreSelection()
        }
    }

    private val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val userMessages = messages.asSharedFlow()

    fun setQuery(value: String) {
        query.value = value
    }

    fun setCatalogSort(sort: CatalogSort) = launch { graph.library.setCatalogSort(sort) }

    fun ensureArt(titles: List<String>) {
        if (titles.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            artGate.withLock {
                val requests = titles.map { title -> TitleCanonicalizer.identityKey(title) to title }
                    .distinctBy { it.first }
                    .filter { (key, _) -> key !in artByTitle.value && artAttempted.add(key) }
                for (page in requests.chunked(ArtWindow.PAGE)) {
                    val batch = linkedMapOf<String, CatalogArt>()
                    graph.steamArt.resolve(page) { key, art ->
                        val flush = synchronized(batch) {
                            batch[key] = art
                            if (batch.size >= 10) {
                                val copy = batch.toMap()
                                batch.clear()
                                copy
                            } else {
                                null
                            }
                        }
                        if (flush != null) publishArt(flush)
                    }
                    val rest = synchronized(batch) {
                        val copy = batch.toMap()
                        batch.clear()
                        copy
                    }
                    publishArt(rest)
                }
            }
        }
    }

    private fun publishArt(update: Map<String, CatalogArt>, overwrite: Boolean = true) {
        if (update.isEmpty()) return
        synchronized(artPublish) {
            artByTitle.value = if (overwrite) artByTitle.value + update else update + artByTitle.value
        }
    }

    fun refresh() = launch { graph.library.refreshAll() }

    fun rescan() = launch { graph.library.rescan() }

    fun select(canonicalTitle: String) {
        graph.library.select(canonicalTitle)
        launch { graph.library.persistSelection(canonicalTitle) }
    }

    fun addRemote(url: String) = launch { graph.library.addRemote(url) }

    fun updateRemote(id: String, url: String) = launch { graph.library.updateRemote(id, url) }

    fun importLocal(uri: String, displayName: String) = launch { graph.library.importLocal(uri, displayName) }

    fun remove(id: String) = launch { graph.library.remove(id) }

    fun setFolder(uri: String) = launch { graph.library.setFolder(uri) }

    fun setUseSecondScreen(enabled: Boolean) = launch { graph.library.setUseSecondScreen(enabled) }

    fun setDeleteArchiveAfterExtract(enabled: Boolean) = launch { graph.library.setDeleteArchiveAfterExtract(enabled) }

    fun openZArchiver(archiveUri: String, displayName: String) {
        val opened = ZArchiverLaunch.open(getApplication(), Uri.parse(archiveUri), displayName)
        if (!opened) messages.tryEmit("ZArchiver is not installed.")
    }

    fun startSolve(sourceId: String, url: String, displayId: Int) {
        graph.library.beginSolve(sourceId, url, displayId)
    }

    fun cancelSolve() {
        ThorFeedsApp.graph(getApplication()).webFeeds.cancelLoad()
        graph.library.clearSolve()
    }

    fun completeSolve(sourceId: String, body: String) = launch {
        graph.library.acceptWebFeed(sourceId, body)
        graph.library.clearSolve()
    }

    fun noteSolveFailure(message: String) {
        messages.tryEmit(message)
    }

    fun retryDownload(releaseId: String) {
        val record = graph.downloads.records.value.firstOrNull { it.releaseId == releaseId }
        if (record?.kind == DownloadRecord.KIND_EXTRACT) extractExisting(releaseId) else startDownload(releaseId)
    }

    fun deleteDownload(releaseId: String) {
        graph.downloads.deleteDownload(releaseId)
    }

    fun startDownload(releaseId: String) {
        if (graph.downloads.isRunning()) {
            messages.tryEmit("A download is already running.")
            return
        }
        val app = getApplication<Application>()
        val intent = Intent(app, DownloadService::class.java)
            .setAction(DownloadService.ACTION_START)
            .putExtra(DownloadService.EXTRA_RELEASE_ID, releaseId)
        try {
            ContextCompat.startForegroundService(app, intent)
        } catch (_: Exception) {
            graph.downloads.start(releaseId)
        }
    }

    fun extractExisting(releaseId: String) {
        if (graph.downloads.isRunning()) {
            messages.tryEmit("A download is already running.")
            return
        }
        val app = getApplication<Application>()
        val intent = Intent(app, DownloadService::class.java)
            .setAction(DownloadService.ACTION_EXTRACT)
            .putExtra(DownloadService.EXTRA_RELEASE_ID, releaseId)
        try {
            ContextCompat.startForegroundService(app, intent)
        } catch (_: Exception) {
            graph.downloads.extractExisting(releaseId)
        }
    }

    fun cancelDownload() {
        val app = getApplication<Application>()
        app.startService(
            Intent(app, DownloadService::class.java).setAction(DownloadService.ACTION_CANCEL),
        )
        graph.downloads.cancel()
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (error: Exception) {
                messages.emit(error.message ?: "Something went wrong")
            }
        }
    }
}
