package com.thorfeeds.catalog.net

import com.thorfeeds.catalog.core.UriHosts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class ReleaseDownloader(
    private val router: MirrorRouter,
    private val fetcher: FileFetcher,
) {
    suspend fun download(uris: List<String>, sink: ByteSink, listener: DownloadListener): List<String> {
        val links = uris.map { it.trim() }.filter { it.isNotEmpty() }
        if (links.isEmpty()) {
            throw AttemptException(
                phase = DownloadPhases.CONNECTING,
                message = "This release has no download links",
            )
        }
        val failures = mutableListOf<String>()
        val saved = mutableListOf<String>()
        for ((index, uri) in links.withIndex()) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (TransferControl.isCancelled()) throw CancellationException("Download cancelled")
            val label = UriHosts.label(uri)
            TransferControl.phase = DownloadPhases.CONNECTING
            listener.onMirrorStarting(index, links.size, label)
            try {
                val resolved = withContext(Dispatchers.IO) { router.resolve(uri) }
                if (resolved.files.isEmpty()) {
                    throw AttemptException(
                        phase = DownloadPhases.CONTENTS,
                        message = "$label did not include a file link, so this mirror was skipped.",
                    )
                }
                for (file in resolved.files) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (TransferControl.isCancelled()) throw CancellationException("Download cancelled")
                    TransferControl.phase = DownloadPhases.CONNECTING
                    val savedName = fetcher.fetch(file, sink) { read, total ->
                        listener.onProgress(file.fileName, read, total)
                    }
                    saved += savedName
                }
                return saved
            } catch (cancelled: CancellationException) {
                val report = AttemptReport.format(
                    host = label,
                    mirrorIndex = index,
                    mirrorCount = links.size,
                    phase = TransferControl.phase.ifBlank { DownloadPhases.CONNECTING },
                    httpStatus = null,
                    apiStatus = "cancel",
                    detail = "Download cancelled.",
                )
                val text = AttemptReport.join(failures + report)
                listener.onMirrorFailed(index, text)
                throw CancellationException(text)
            } catch (error: Exception) {
                val report = AttemptReport.fromThrowable(label, index, links.size, error)
                failures += report
                listener.onMirrorFailed(index, AttemptReport.join(failures))
            }
        }
        throw MirrorException(AttemptReport.join(failures).ifBlank { "All mirrors failed" })
    }
}
