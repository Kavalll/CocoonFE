package com.thorfeeds.catalog.download

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.thorfeeds.catalog.core.Names
import com.thorfeeds.catalog.data.LibraryRepository
import com.thorfeeds.catalog.data.SafByteSink
import com.thorfeeds.catalog.data.toParsed
import com.thorfeeds.catalog.net.DownloadListener
import com.thorfeeds.catalog.net.ReleaseDownloader
import com.thorfeeds.catalog.net.publicMirrorError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

enum class DownloadPhase {
    Connecting,
    Running,
    Completed,
    Failed,
    Cancelled,
}

data class DownloadSnapshot(
    val releaseId: String,
    val canonicalTitle: String,
    val phase: DownloadPhase,
    val mirrorIndex: Int,
    val mirrorCount: Int,
    val mirrorLabel: String,
    val bytesRead: Long,
    val totalBytes: Long?,
    val fileName: String?,
    val message: String?,
) {
    val terminal: Boolean
        get() = phase == DownloadPhase.Completed || phase == DownloadPhase.Failed || phase == DownloadPhase.Cancelled
}

class DownloadController(
    private val context: Context,
    private val library: LibraryRepository,
    private val downloader: ReleaseDownloader,
) {
    val snapshot = MutableStateFlow<DownloadSnapshot?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun isRunning(): Boolean = job?.isActive == true

    fun start(releaseId: String) {
        synchronized(this) {
            if (job?.isActive == true) return
            job = scope.launch { run(releaseId) }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    private suspend fun run(releaseId: String) {
        val entity = library.readRelease(releaseId)
        if (entity == null) {
            snapshot.value = failed(releaseId, "Game", "That release is no longer in the catalog.")
            return
        }
        val parsed = entity.toParsed()
        val base = DownloadSnapshot(
            releaseId = releaseId,
            canonicalTitle = entity.canonicalTitle,
            phase = DownloadPhase.Connecting,
            mirrorIndex = 0,
            mirrorCount = parsed.uris.size.coerceAtLeast(1),
            mirrorLabel = "…",
            bytesRead = 0L,
            totalBytes = null,
            fileName = null,
            message = null,
        )
        val folder = library.currentFolder()
        if (folder.isNullOrBlank()) {
            snapshot.value = base.copy(phase = DownloadPhase.Failed, message = "Choose a games folder first.")
            return
        }
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(folder))
        if (tree == null || !tree.canWrite()) {
            snapshot.value = base.copy(
                phase = DownloadPhase.Failed,
                message = "The games folder permission expired. Choose it again.",
            )
            return
        }
        val directoryName = Names.sanitizeFolder(entity.canonicalTitle)
        val gameDir = tree.listFiles().firstOrNull { it.isDirectory && it.name.equals(directoryName, ignoreCase = true) }
            ?: tree.createDirectory(directoryName)
        if (gameDir == null) {
            snapshot.value = base.copy(phase = DownloadPhase.Failed, message = "Couldn't create a folder for this game.")
            return
        }
        snapshot.value = base
        val sink = SafByteSink(context.contentResolver, gameDir)
        try {
            downloader.download(parsed.uris, sink, object : DownloadListener {
                override fun onMirrorStarting(index: Int, count: Int, label: String) {
                    snapshot.value = (snapshot.value ?: base).copy(
                        phase = DownloadPhase.Connecting,
                        mirrorIndex = index,
                        mirrorCount = count,
                        mirrorLabel = label,
                        bytesRead = 0L,
                        totalBytes = null,
                        message = null,
                    )
                }

                override fun onMirrorFailed(index: Int, message: String) {
                    val current = snapshot.value ?: base
                    snapshot.value = current.copy(message = message)
                }

                override fun onProgress(fileName: String, bytesRead: Long, totalBytes: Long?) {
                    val current = snapshot.value ?: base
                    snapshot.value = current.copy(
                        phase = DownloadPhase.Running,
                        bytesRead = bytesRead,
                        totalBytes = totalBytes,
                        fileName = fileName,
                    )
                }
            })
            snapshot.value = (snapshot.value ?: base).copy(
                phase = DownloadPhase.Completed,
                message = "Saved in $directoryName",
            )
            library.rescan()
        } catch (cancelled: CancellationException) {
            snapshot.value = (snapshot.value ?: base).copy(
                phase = DownloadPhase.Cancelled,
                message = "Download cancelled",
            )
        } catch (error: Exception) {
            snapshot.value = (snapshot.value ?: base).copy(
                phase = DownloadPhase.Failed,
                message = publicMirrorError("Download", error),
            )
        }
    }

    private fun failed(releaseId: String, title: String, message: String): DownloadSnapshot {
        return DownloadSnapshot(
            releaseId = releaseId,
            canonicalTitle = title,
            phase = DownloadPhase.Failed,
            mirrorIndex = 0,
            mirrorCount = 0,
            mirrorLabel = "",
            bytesRead = 0L,
            totalBytes = null,
            fileName = null,
            message = message,
        )
    }
}
