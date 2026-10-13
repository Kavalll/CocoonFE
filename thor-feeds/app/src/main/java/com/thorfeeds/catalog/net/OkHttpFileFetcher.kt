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
        TransferControl.phase = DownloadPhases.CONNECTING
        val existing = try {
            if (allowRange) sink.existing(partialName(file.fileName), file.sourceKey) else ExistingPartial(0, false)
        } catch (error: Exception) {
            throw writingFailure(error)
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
            return TransferControl.use(call) {
                call.execute().use { response ->
                    readBody(file, sink, onProgress, allowRange, existing, range, response, job)
                }
            }
        } catch (_: RangeRejected) {
            call.cancel()
            return download(file, sink, onProgress, allowRange = false)
        } catch (cancelled: CancellationException) {
            call.cancel()
            throw cancelled
        } catch (attempt: AttemptException) {
            call.cancel()
            throw attempt
        } catch (io: IOException) {
            call.cancel()
            if (job?.isActive == false || TransferControl.isCancelled()) {
                throw CancellationException("Download cancelled")
            }
            throw io.asAttempt(TransferControl.phase)
        }
    }

    private fun readBody(
        file: RemoteFile,
        sink: ByteSink,
        onProgress: (bytesRead: Long, totalBytes: Long?) -> Unit,
        allowRange: Boolean,
        existing: ExistingPartial,
        range: String?,
        response: okhttp3.Response,
        job: Job?,
    ): String {
        if (response.code == 416 && range != null) {
            throw RangeRejected()
        }
        if (response.code != 200 && response.code != 206) {
            throw AttemptException(
                phase = DownloadPhases.CONNECTING,
                httpStatus = response.code,
                message = "The host refused the file (${response.code}).",
            )
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
        try {
            sink.writeMeta(partial, file.sourceKey, acceptsRange)
        } catch (error: Exception) {
            throw writingFailure(error)
        }
        val output = try {
            sink.open(partial, append = appending)
        } catch (error: Exception) {
            throw writingFailure(error)
        }
        TransferControl.phase = DownloadPhases.TRANSFERRING
        var finished = false
        var reject: AttemptException? = null
        try {
            val source = response.body?.byteStream() ?: throw AttemptException(
                phase = DownloadPhases.CONNECTING,
                httpStatus = response.code,
                message = "The host returned an empty file.",
            )
            val buffer = ByteArray(256 * 1024)
            var readTotal = if (appending) existing.bytes else 0L
            var checkedPrefix = false
            var lastEmit = 0L
            while (true) {
                if (job?.isActive == false || TransferControl.isCancelled()) {
                    throw CancellationException("Download cancelled")
                }
                val count = try {
                    source.read(buffer)
                } catch (io: IOException) {
                    if (TransferControl.isCancelled()) throw CancellationException("Download cancelled")
                    throw io.asAttempt(DownloadPhases.TRANSFERRING)
                }
                if (count < 0) break
                if (!checkedPrefix) {
                    checkedPrefix = true
                    if (DownloadPlan.looksLikeHtmlPrefix(buffer, count)) {
                        reject = AttemptException(
                            phase = DownloadPhases.TRANSFERRING,
                            httpStatus = response.code,
                            message = "Host returned a web page instead of a file",
                        )
                        break
                    }
                }
                TransferControl.phase = DownloadPhases.WRITING
                try {
                    output.write(buffer, 0, count)
                } catch (error: Exception) {
                    throw writingFailure(error)
                }
                TransferControl.phase = DownloadPhases.TRANSFERRING
                readTotal += count
                val now = System.currentTimeMillis()
                if (now - lastEmit > 200) {
                    lastEmit = now
                    onProgress(readTotal, total)
                }
            }
            try {
                output.flush()
            } catch (error: Exception) {
                throw writingFailure(error)
            }
            finished = reject == null
            if (finished) onProgress(readTotal, total ?: readTotal)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (attempt: AttemptException) {
            throw attempt
        } finally {
            try {
                output.close()
            } catch (error: Exception) {
                if (reject == null && finished) throw writingFailure(error)
            }
        }
        if (reject != null) {
            try {
                sink.discard(partial)
            } catch (_: Exception) {
                // The failure that matters is the rejected body.
            }
            throw reject
        }
        if (finished) {
            try {
                sink.commit(partial)
            } catch (error: Exception) {
                throw writingFailure(error)
            }
        }
        return fileName
    }

    private fun writingFailure(error: Exception): AttemptException {
        if (error is CancellationException) throw error
        if (error is AttemptException) return error
        return AttemptException(
            phase = DownloadPhases.WRITING,
            message = error.message?.ifBlank { null } ?: "Couldn't write the download file.",
        )
    }

    private class RangeRejected : Exception()

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
