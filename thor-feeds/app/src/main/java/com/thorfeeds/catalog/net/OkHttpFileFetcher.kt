package com.thorfeeds.catalog.net

import com.thorfeeds.catalog.core.DownloadPlan
import com.thorfeeds.catalog.core.Names
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLDecoder

class OkHttpFileFetcher(private val client: OkHttpClient) : FileFetcher {
    override suspend fun fetch(
        file: RemoteFile,
        sink: ByteSink,
        onProgress: (bytesRead: Long, totalBytes: Long?) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        download(file, sink, onProgress, allowRange = true)
    }

    private suspend fun download(
        file: RemoteFile,
        sink: ByteSink,
        onProgress: (bytesRead: Long, totalBytes: Long?) -> Unit,
        allowRange: Boolean,
    ): String {
        val job = kotlin.coroutines.coroutineContext[Job]
        val existing = if (allowRange) {
            sink.existing(partialName(file.fileName), file.sourceKey)
        } else {
            ExistingPartial(0, false)
        }
        val range = if (allowRange) DownloadPlan.rangeHeader(existing.bytes, existing.acceptsRange) else null
        val requestBuilder = Request.Builder()
            .url(file.url)
            .header("Accept-Encoding", "identity")
        file.headers.forEach { (key, value) -> requestBuilder.header(key, value) }
        if (range != null) requestBuilder.header("Range", range)
        val call = client.newCall(requestBuilder.build())
        job?.invokeOnCompletion { cause ->
            if (cause is CancellationException) call.cancel()
        }
        try {
            call.execute().use { response ->
                if (response.code == 416 && range != null) {
                    return download(file, sink, onProgress, allowRange = false)
                }
                if (response.code != 200 && response.code != 206) {
                    throw MirrorException("Download failed (${response.code})")
                }
                val appending = response.code == 206 && range != null
                val headerName = fileNameFromContentDisposition(response.header("Content-Disposition"))
                val fileName = if (!appending && !headerName.isNullOrBlank()) {
                    Names.sanitizeFile(headerName)
                } else {
                    file.fileName
                }
                val partial = partialName(fileName)
                val acceptsRange = response.code == 206 ||
                    response.header("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true
                val total = DownloadPlan.totalBytes(
                    sentRange = appending,
                    existing = if (appending) existing.bytes else 0L,
                    contentLength = response.body?.contentLength() ?: -1L,
                    contentRange = response.header("Content-Range"),
                )
                sink.writeMeta(partial, file.sourceKey, acceptsRange)
                val output = sink.open(partial, append = appending)
                var finished = false
                var reject: MirrorException? = null
                try {
                    val source = response.body?.byteStream() ?: throw MirrorException("Empty download")
                    val buffer = ByteArray(256 * 1024)
                    var readTotal = if (appending) existing.bytes else 0L
                    var checkedPrefix = false
                    var lastEmit = 0L
                    while (true) {
                        if (job?.isActive == false) throw CancellationException("Download cancelled")
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (!checkedPrefix) {
                            checkedPrefix = true
                            if (DownloadPlan.looksLikeHtmlPrefix(buffer, count)) {
                                reject = MirrorException("Host returned a web page instead of a file")
                                break
                            }
                        }
                        output.write(buffer, 0, count)
                        readTotal += count
                        val now = System.currentTimeMillis()
                        if (now - lastEmit > 200) {
                            lastEmit = now
                            onProgress(readTotal, total)
                        }
                    }
                    output.flush()
                    finished = reject == null
                    if (finished) onProgress(readTotal, total ?: readTotal)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } finally {
                    output.close()
                }
                if (reject != null) {
                    sink.discard(partial)
                    throw reject
                }
                if (finished) sink.commit(partial)
                return fileName
            }
        } catch (cancelled: CancellationException) {
            call.cancel()
            throw cancelled
        } catch (io: IOException) {
            call.cancel()
            if (job?.isActive == false) {
                throw CancellationException("Download cancelled")
            }
            throw MirrorException("The connection dropped")
        }
    }

    companion object {
        fun partialName(fileName: String): String = "$fileName.partial"

        fun fileNameFromContentDisposition(header: String?): String? {
            if (header.isNullOrBlank()) return null
            val encoded = Regex("""filename\*\s*=\s*(?:UTF-8''|utf-8'')([^;]+)""", RegexOption.IGNORE_CASE)
                .find(header)
            if (encoded != null) {
                return URLDecoder.decode(encoded.groupValues[1].trim().trim('"'), Charsets.UTF_8.name())
            }
            val plain = Regex("""filename\s*=\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE).find(header)
            return plain?.groupValues?.get(1)?.trim()
        }
    }
}
