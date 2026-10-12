package com.thorfeeds.catalog.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.thorfeeds.catalog.ThorFeedsApp
import com.thorfeeds.catalog.download.DownloadService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CatalogViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = ThorFeedsApp.graph(app)

    val releases = graph.library.releases.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val sources = graph.library.sources.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val folderUri = graph.library.folderUri.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val useSecondScreen = graph.library.useSecondScreen.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val onDeviceKeys = graph.library.onDeviceKeys
    val refreshing = graph.library.refreshing
    val selection = graph.library.selection
    val folderLabel = graph.library.folderLabel
    val folderProblem = graph.library.folderProblem
    val download = graph.downloads.snapshot
    val solveSession = graph.library.solveSession
    val query = MutableStateFlow("")

    private val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val userMessages = messages.asSharedFlow()

    fun setQuery(value: String) {
        query.value = value
    }

    fun refresh() = launch { graph.library.refreshAll() }

    fun rescan() = launch { graph.library.rescan() }

    fun select(canonicalTitle: String) {
        graph.library.select(canonicalTitle)
    }

    fun addRemote(url: String) = launch { graph.library.addRemote(url) }

    fun updateRemote(id: String, url: String) = launch { graph.library.updateRemote(id, url) }

    fun importLocal(uri: String, displayName: String) = launch { graph.library.importLocal(uri, displayName) }

    fun remove(id: String) = launch { graph.library.remove(id) }

    fun setFolder(uri: String) = launch { graph.library.setFolder(uri) }

    fun setUseSecondScreen(enabled: Boolean) = launch { graph.library.setUseSecondScreen(enabled) }

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
