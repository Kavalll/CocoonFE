package com.thorfeeds.catalog.download

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.thorfeeds.catalog.core.ArchiveFormat
import com.thorfeeds.catalog.core.ArchiveLayout
import com.thorfeeds.catalog.core.GameArchiveIndex
import com.thorfeeds.catalog.core.LocatedArchive
import com.thorfeeds.catalog.core.Names
import com.thorfeeds.catalog.core.UnpackException
import com.thorfeeds.catalog.data.FolderScanner
import com.thorfeeds.catalog.data.GameArchiveExtractor
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

enum class DownloadPhase {
    Connecting,
    Running,
    Extracting,
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
    val archiveUri: String? = null,
    val offerZArchiver: Boolean = false,
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

    fun extractExisting(releaseId: String) {
        synchronized(this) {
            if (job?.isActive == true) return
            TransferControl.reset()
            job = scope.launch { runExtract(releaseId) }
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
            val savedNames = downloader.download(parsed.uris, sink, object : DownloadListener {
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
            extractSaved(gameDir, directoryName, savedNames, base)
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

    private suspend fun runExtract(releaseId: String) {
        val entity = library.readRelease(releaseId)
        if (entity == null) {
            snapshot.value = failed(releaseId, "Game", DownloadPhases.EXTRACTING, "That release is no longer in the catalog.")
            return
        }
        val parsed = entity.toParsed()
        val base = DownloadSnapshot(
            releaseId = releaseId,
            canonicalTitle = entity.canonicalTitle,
            phase = DownloadPhase.Extracting,
            mirrorIndex = 0,
            mirrorCount = parsed.uris.size.coerceAtLeast(1),
            mirrorLabel = "archive",
            bytesRead = 0L,
            totalBytes = null,
            fileName = null,
            message = null,
        )
        val folder = library.currentFolder()
        if (folder.isNullOrBlank()) {
            snapshot.value = base.copy(
                phase = DownloadPhase.Failed,
                message = detail(base, DownloadPhases.EXTRACTING, "Choose a games folder first."),
            )
            return
        }
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(folder))
        if (tree == null || !tree.canWrite()) {
            snapshot.value = base.copy(
                phase = DownloadPhase.Failed,
                message = detail(base, DownloadPhases.EXTRACTING, "The games folder permission expired. Choose it again."),
            )
            return
        }
        val directoryName = Names.sanitizeFolder(entity.canonicalTitle)
        val gameDir = tree.listFiles().firstOrNull { it.isDirectory && it.name.equals(directoryName, ignoreCase = true) }
            ?: tree.createDirectory(directoryName)
        if (gameDir == null) {
            snapshot.value = base.copy(
                phase = DownloadPhase.Failed,
                message = detail(base, DownloadPhases.EXTRACTING, "Couldn't create a folder for this game."),
            )
            return
        }
        snapshot.value = base
        try {
            val archives = existingArchives(tree, gameDir, entity.canonicalTitle)
            extractArchives(gameDir, directoryName, archives, base, missingMeansSaved = false)
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

    private fun existingArchives(
        tree: androidx.documentfile.provider.DocumentFile,
        gameDir: androidx.documentfile.provider.DocumentFile,
        canonicalTitle: String,
    ): List<ArchiveTarget> {
        val scanned = FolderScanner(context).scan(tree.uri.toString()).archives
        return GameArchiveIndex.forTitle(canonicalTitle, scanned).mapNotNull { located ->
            val document = findArchive(tree, located) ?: return@mapNotNull null
            val format = ArchiveLayout.formatOf(located.fileName) ?: return@mapNotNull null
            val output = if (located.folderName == null) gameDir else document.parentFile ?: gameDir
            ArchiveTarget(located.fileName, format, document, output)
        }
    }

    private fun findArchive(
        tree: androidx.documentfile.provider.DocumentFile,
        located: LocatedArchive,
    ): androidx.documentfile.provider.DocumentFile? {
        val parent = if (located.folderName == null) {
            tree
        } else {
            tree.listFiles().firstOrNull { it.isDirectory && it.name == located.folderName } ?: return null
        }
        return parent.listFiles().firstOrNull { it.isFile && it.name == located.fileName }
    }

    private suspend fun extractSaved(
        gameDir: androidx.documentfile.provider.DocumentFile,
        directoryName: String,
        savedNames: List<String>,
        base: DownloadSnapshot,
    ) {
        val archives = savedNames.mapNotNull { name ->
            val format = ArchiveLayout.formatOf(name) ?: return@mapNotNull null
            val document = gameDir.findFile(name)?.takeIf { it.isFile } ?: return@mapNotNull null
            ArchiveTarget(name, format, document, gameDir)
        }
        extractArchives(gameDir, directoryName, archives, base, missingMeansSaved = true)
    }

    private suspend fun extractArchives(
        gameDir: androidx.documentfile.provider.DocumentFile,
        directoryName: String,
        archives: List<ArchiveTarget>,
        base: DownloadSnapshot,
        missingMeansSaved: Boolean,
    ) {
        if (archives.isEmpty()) {
            if (missingMeansSaved) {
                finishDownload(directoryName, "Saved in $directoryName")
            } else {
                snapshot.value = (snapshot.value ?: base).copy(
                    phase = DownloadPhase.Failed,
                    message = detail(base, DownloadPhases.EXTRACTING, "No zip or rar is in this game folder."),
                )
            }
            return
        }
        val deleteAfter = library.deleteArchiveAfterExtract()
        val notes = mutableListOf<String>()
        for ((name, format, document, outputDir) in archives) {
            currentCoroutineContext().ensureActive()
            if (TransferControl.isCancelled()) throw CancellationException("Download cancelled")
            if (document.length() <= 0L) {
                failExtract(base, name, format, document.uri.toString(), UnpackException(format.label, format == ArchiveFormat.RAR, "The ${format.label} archive is empty, so it was left in the game folder."))
                return
            }
            TransferControl.phase = DownloadPhases.EXTRACTING
            val current = snapshot.value ?: base
            snapshot.value = current.copy(
                phase = DownloadPhase.Extracting,
                fileName = name,
                bytesRead = 0L,
                totalBytes = null,
                archiveUri = null,
                offerZArchiver = false,
            )
            var lastEmit = 0L
            try {
                GameArchiveExtractor.extract(
                    resolver = context.contentResolver,
                    archive = document,
                    format = format,
                    cancelled = { TransferControl.isCancelled() },
                    onProgress = { written, total ->
                        val now = System.currentTimeMillis()
                        if (now - lastEmit > 200 || written == total) {
                            lastEmit = now
                            val shown = snapshot.value ?: current
                            snapshot.value = shown.copy(
                                phase = DownloadPhase.Extracting,
                                fileName = name,
                                bytesRead = written,
                                totalBytes = total,
                            )
                        }
                    },
                    outputDirectory = outputDir,
                )
                if (deleteAfter) {
                    if (document.exists() && !document.delete()) {
                        notes += "$name was extracted, but the archive could not be removed."
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: UnpackException) {
                failExtract(base, name, format, document.uri.toString(), error)
                return
            } catch (error: Exception) {
                failExtract(
                    base,
                    name,
                    format,
                    document.uri.toString(),
                    UnpackException(
                        format.label,
                        format == ArchiveFormat.RAR,
                        error.message ?: "Extraction failed, so the archive was left in the game folder.",
                        error,
                    ),
                )
                return
            }
        }
        val tail = when {
            notes.isNotEmpty() -> notes.joinToString(" ")
            deleteAfter -> "The archive was removed."
            else -> "The archive is still in the folder."
        }
        finishDownload(directoryName, "Extracted in $directoryName. $tail")
    }

    private fun finishDownload(directoryName: String, text: String) {
        val current = snapshot.value ?: return
        val previous = current.message?.takeIf { it.contains("Phase:") && !it.contains("Phase: extracting") }
        snapshot.value = current.copy(
            phase = DownloadPhase.Completed,
            message = if (previous.isNullOrBlank()) text else "$previous\n\n$text",
            archiveUri = null,
            offerZArchiver = false,
        )
    }

    private fun failExtract(
        base: DownloadSnapshot,
        fileName: String,
        format: ArchiveFormat,
        archiveUri: String,
        error: UnpackException,
    ) {
        val current = snapshot.value ?: base
        val installed = error.offerZArchiver && ZArchiverLaunch.installed(context)
        val detail = buildString {
            append(error.message ?: "Extraction failed, so the archive was left in the game folder.")
            if (error.offerZArchiver && !installed) {
                append("\nZArchiver (ru.zdevs.zarchiver) is not installed. It can open this rar if you install it.")
            } else if (installed) {
                append("\nYou can extract this rar with ZArchiver.")
            }
        }
        val report = AttemptReport.format(
            host = fileName,
            mirrorIndex = current.mirrorIndex,
            mirrorCount = current.mirrorCount,
            phase = DownloadPhases.EXTRACTING,
            httpStatus = null,
            apiStatus = format.label,
            detail = detail,
        )
        val previous = current.message?.takeIf { it.contains("Phase:") && !it.contains("Phase: extracting") }
        snapshot.value = current.copy(
            phase = DownloadPhase.Failed,
            fileName = fileName,
            message = if (previous.isNullOrBlank()) report else "$previous\n\n$report",
            archiveUri = if (installed) archiveUri else null,
            offerZArchiver = installed,
        )
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

    private data class ArchiveTarget(
        val name: String,
        val format: ArchiveFormat,
        val document: androidx.documentfile.provider.DocumentFile,
        val outputDir: androidx.documentfile.provider.DocumentFile,
    )

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
