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
    suspend fun download(uris: List<String>, sink: ByteSink, listener: DownloadListener) {
        val links = uris.map { it.trim() }.filter { it.isNotEmpty() }
        if (links.isEmpty()) throw MirrorException("This release has no download links")
        var lastMessage = "All mirrors failed"
        for ((index, uri) in links.withIndex()) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val label = UriHosts.label(uri)
            listener.onMirrorStarting(index, links.size, label)
            try {
                val resolved = withContext(Dispatchers.IO) { router.resolve(uri) }
                if (resolved.files.isEmpty()) throw MirrorException("$label did not include a file")
                for (file in resolved.files) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    fetcher.fetch(file, sink) { read, total ->
                        listener.onProgress(file.fileName, read, total)
                    }
                }
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                lastMessage = publicMirrorError(label, error)
                listener.onMirrorFailed(index, lastMessage)
            }
        }
        throw MirrorException(lastMessage)
    }

}

fun publicMirrorError(label: String, error: Throwable): String {
    val raw = error.message.orEmpty()
        .replace(Regex("""https?://\S+"""), "a link")
        .replace(Regex("""\s+"""), " ")
        .trim()
    val clipped = raw.take(180)
    return if (clipped.isBlank()) "$label failed" else "$label: $clipped"
}
