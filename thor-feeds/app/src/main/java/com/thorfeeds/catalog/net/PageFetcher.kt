package com.thorfeeds.catalog.net

import kotlinx.coroutines.CancellationException
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

data class PageBody(
    val code: Int,
    val contentType: String?,
    val body: String,
    val finalUrl: String,
    val isFile: Boolean,
)

class PageFetcher(private val client: OkHttpClient) {
    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long? = null,
        phase: String = DownloadPhases.CONNECTING,
    ): PageBody {
        return execute(Request.Builder().url(url).get().applyHeaders(headers).build(), timeoutMs, phase)
    }

    fun postForm(
        url: String,
        fields: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long? = null,
        phase: String = DownloadPhases.CONNECTING,
    ): PageBody {
        val form = FormBody.Builder().apply {
            fields.forEach { (key, value) -> add(key, value) }
        }.build()
        return execute(Request.Builder().url(url).post(form).applyHeaders(headers).build(), timeoutMs, phase)
    }

    fun postEmpty(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long? = null,
        phase: String = DownloadPhases.CONNECTING,
    ): PageBody {
        val body = ByteArray(0).toRequestBody(null)
        return execute(Request.Builder().url(url).post(body).applyHeaders(headers).build(), timeoutMs, phase)
    }

    fun postJson(
        url: String,
        json: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long? = null,
        phase: String = DownloadPhases.CONNECTING,
    ): PageBody {
        val body = json.toRequestBody(JSON)
        val request = Request.Builder().url(url).post(body).applyHeaders(headers).build()
        return execute(request, timeoutMs, phase)
    }

    private fun execute(request: Request, timeoutMs: Long?, phase: String): PageBody {
        TransferControl.phase = phase
        val call = clientFor(timeoutMs).newCall(request)
        try {
            return TransferControl.use(call) {
                call.execute().use { response ->
                    read(response)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (attempt: AttemptException) {
            throw attempt
        } catch (io: IOException) {
            if (TransferControl.isCancelled()) throw CancellationException("Download cancelled")
            throw io.asAttempt(phase)
        }
    }

    private fun read(response: okhttp3.Response): PageBody {
            val type = response.header("Content-Type")
            val length = response.body?.contentLength() ?: -1L
            val disposition = response.header("Content-Disposition").orEmpty()
            if (isAttachment(type, length, disposition)) {
                return PageBody(
                    code = response.code,
                    contentType = type,
                    body = "",
                    finalUrl = response.request.url.toString(),
                    isFile = true,
                )
            }
            val text = response.peekBody(MAX_PAGE_BYTES).string()
            return PageBody(
                code = response.code,
                contentType = type,
                body = text,
                finalUrl = response.request.url.toString(),
                isFile = false,
            )
    }

    private fun clientFor(timeoutMs: Long?): OkHttpClient {
        if (timeoutMs == null || timeoutMs <= 0L) return client
        return client.newBuilder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
    }

    private fun isAttachment(type: String?, length: Long, disposition: String): Boolean {
        if (disposition.contains("attachment", ignoreCase = true)) return true
        val lower = type?.lowercase().orEmpty()
        val textual = lower.contains("html") ||
            lower.contains("json") ||
            lower.contains("xml") ||
            lower.contains("javascript") ||
            lower.contains("ecmascript") ||
            lower.startsWith("text/") ||
            (lower.isBlank() && length in 0..MAX_PAGE_BYTES)
        if (textual) return false
        return length < 0 || length > MAX_PAGE_BYTES || lower.contains("octet") || lower.contains("zip")
    }

    private fun Request.Builder.applyHeaders(headers: Map<String, String>): Request.Builder {
        headers.forEach { (key, value) -> header(key, value) }
        return this
    }

    companion object {
        const val MAX_PAGE_BYTES = 2_000_000L
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
