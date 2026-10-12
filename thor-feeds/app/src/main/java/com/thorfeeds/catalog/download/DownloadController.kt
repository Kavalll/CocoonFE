package com.thorfeeds.catalog.download

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.thorfeeds.catalog.core.Names
import com.thorfeeds.catalog.data.LibraryRepository
import com.thorfeeds.catalog.data.SafByteSink
import com.thorfeeds.catalog.data.toParsed
import com.thorfeeds.catalog.net.AttemptReport
import com.thorfeeds.catalog.net.DownloadListener
import com.thorfeeds.catalog.net.DownloadPhases
import com.thorfeeds.catalog.net.ReleaseDownloader
import com.thorfeeds.catalog.net.TransferControl
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
            TransferControl.reset()
            job = scope.launch { run(releaseId) }
        }
    }

    fun cancel() {
        synchronized(this) {
            TransferControl.cancel()
            job?.cancel()
        }
    }

    private suspend fun run(releaseId: String) {
        val entity = library.readRelease(releaseId)
        if (entity == null) {
            snapshot.value = failed(releaseId, "Game", DownloadPhases.CONNECTING, "That release is no longer in the catalog.")
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
            snapshot.value = base.copy(
                phase = DownloadPhase.Failed,
                message = detail(base, DownloadPhases.WRITING, "Choose a games folder first."),
            )
            return
        }
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(folder))
        if (tree == null || !tree.canWrite()) {
            snapshot.value = base.copy(
                phase = DownloadPhase.Failed,
                message = detail(base, DownloadPhases.WRITING, "The games folder permission expired. Choose it again."),
            )
            return
        }
        val directoryName = Names.sanitizeFolder(entity.canonicalTitle)
        val gameDir = tree.listFiles().firstOrNull { it.isDirectory && it.name.equals(directoryName, ignoreCase = true) }
            ?: tree.createDirectory(directoryName)
        if (gameDir == null) {
            snapshot.value = base.copy(
                phase = DownloadPhase.Failed,
                message = detail(base, DownloadPhases.WRITING, "Couldn't create a folder for this game."),
            )
            return
        }
        snapshot.value = base
        val sink = SafByteSink(context.contentResolver, gameDir)
        try {
            downloader.download(parsed.uris, sink, object : DownloadListener {
                override fun onMirrorStarting(index: Int, count: Int, label: String) {
                    val current = snapshot.value ?: base
                    snapshot.value = current.copy(
                        phase = DownloadPhase.Connecting,
                        mirrorIndex = index,
                        mirrorCount = count,
                        mirrorLabel = label,
                        bytesRead = 0L,
                        totalBytes = null,
                        fileName = null,
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
            val current = snapshot.value ?: base
            val saved = "Saved in $directoryName"
            val previous = current.message?.takeIf { it.contains("Phase:") }
            snapshot.value = current.copy(
                phase = DownloadPhase.Completed,
                message = if (previous.isNullOrBlank()) saved else "$previous\n\n$saved",
            )
            library.rescan()
        } catch (cancelled: CancellationException) {
            val current = snapshot.value ?: base
            snapshot.value = current.copy(
                phase = DownloadPhase.Cancelled,
                message = cancelDetail(current),
            )
        } catch (error: Exception) {
            val current = snapshot.value ?: base
            snapshot.value = current.copy(
                phase = DownloadPhase.Failed,
                message = failureDetail(current, error),
            )
        }
    }

    private fun detail(base: DownloadSnapshot, phase: String, text: String): String {
        return AttemptReport.format(
            host = base.mirrorLabel.takeUnless { it.isBlank() || it == "…" } ?: "Thor Feeds",
            mirrorIndex = base.mirrorIndex,
            mirrorCount = base.mirrorCount,
            phase = phase,
            httpStatus = null,
            apiStatus = null,
            detail = text,
        )
    }

    private fun cancelDetail(current: DownloadSnapshot): String {
        if (current.message?.contains("Status: cancel") == true) return current.message
        val report = AttemptReport.format(
            host = current.mirrorLabel.ifBlank { "download" },
            mirrorIndex = current.mirrorIndex,
            mirrorCount = current.mirrorCount,
            phase = TransferControl.phase.ifBlank { DownloadPhases.CONNECTING },
            httpStatus = null,
            apiStatus = "cancel",
            detail = "Download cancelled.",
        )
        val previous = current.message?.takeIf { it.contains("Phase:") }
        return if (previous.isNullOrBlank()) report else "$previous\n\n$report"
    }

    private fun failureDetail(current: DownloadSnapshot, error: Exception): String {
        current.message?.takeIf { it.contains("Phase:") }?.let { return it }
        error.message?.takeIf { it.contains("Phase:") }?.let { return it }
        return AttemptReport.fromThrowable(
            host = current.mirrorLabel.ifBlank { "download" },
            mirrorIndex = current.mirrorIndex,
            mirrorCount = current.mirrorCount,
            error = error,
        )
    }

    private fun failed(releaseId: String, title: String, phase: String, detail: String): DownloadSnapshot {
        return DownloadSnapshot(
            releaseId = releaseId,
            canonicalTitle = title,
            phase = DownloadPhase.Failed,
            mirrorIndex = 0,
            mirrorCount = 1,
            mirrorLabel = "Thor Feeds",
            bytesRead = 0L,
            totalBytes = null,
            fileName = null,
            message = AttemptReport.format(
                host = "Thor Feeds",
                mirrorIndex = 0,
                mirrorCount = 1,
                phase = phase,
                httpStatus = null,
                apiStatus = null,
                detail = detail,
            ),
        )
    }
}
