package com.thorfeeds.catalog.net

import kotlinx.coroutines.CancellationException
import okhttp3.Call
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

object DownloadPhases {
    const val GUEST_TOKEN = "guest token"
    const val WEBSITE_TOKEN = "website token"
    const val CONTENTS = "contents lookup"
    const val CONNECTING = "connecting"
    const val TRANSFERRING = "transferring"
    const val WRITING = "writing the file"
}

class AttemptException(
    val phase: String,
    val httpStatus: Int? = null,
    val apiStatus: String? = null,
    message: String,
) : MirrorException(message) {
    fun withMessage(message: String): AttemptException {
        return AttemptException(phase, httpStatus, apiStatus, message)
    }
}

object AttemptReport {
    fun format(
        host: String,
        mirrorIndex: Int,
        mirrorCount: Int,
        phase: String,
        httpStatus: Int?,
        apiStatus: String?,
        detail: String,
    ): String {
        val lines = mutableListOf<String>()
        lines += "Mirror ${mirrorIndex + 1} of ${mirrorCount.coerceAtLeast(1)} · $host"
        lines += "Phase: $phase"
        if (httpStatus != null && httpStatus > 0) lines += "HTTP $httpStatus"
        if (!apiStatus.isNullOrBlank()) lines += "Status: $apiStatus"
        val clean = sanitizeDetail(detail)
        if (clean.isNotBlank()) lines += clean
        return lines.joinToString("\n")
    }

    fun join(parts: List<String>): String {
        return parts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")
    }

    fun fromThrowable(host: String, mirrorIndex: Int, mirrorCount: Int, error: Throwable): String {
        val attempt = generateSequence(error) { it.cause }.filterIsInstance<AttemptException>().firstOrNull()
        val detail = attempt?.message ?: error.message ?: "The mirror failed."
        return format(
            host = host,
            mirrorIndex = mirrorIndex,
            mirrorCount = mirrorCount,
            phase = attempt?.phase ?: DownloadPhases.CONNECTING,
            httpStatus = attempt?.httpStatus,
            apiStatus = attempt?.apiStatus,
            detail = detail,
        )
    }

    private fun sanitizeDetail(raw: String): String {
        return raw
            .replace(Regex("""https?://\S+"""), "a link")
            .lineSequence()
            .joinToString("\n") { it.trim() }
            .trim()
            .take(500)
    }
}

/**
 * In-flight OkHttp calls for the single active download.
 * Cancel aborts them immediately, including while resolution has not written any bytes.
 */
object TransferControl {
    private val calls = Collections.synchronizedSet(mutableSetOf<Call>())
    private val cancelled = AtomicBoolean(false)

    @Volatile var phase: String = DownloadPhases.CONNECTING

    fun reset() {
        cancelled.set(false)
        phase = DownloadPhases.CONNECTING
    }

    fun isCancelled(): Boolean = cancelled.get()

    fun cancel() {
        cancelled.set(true)
        val active = synchronized(calls) { calls.toList() }
        active.forEach { call ->
            try {
                call.cancel()
            } catch (_: Exception) {
                // The call may already be finished.
            }
        }
    }

    fun <T> use(call: Call, block: () -> T): T {
        if (cancelled.get()) {
            call.cancel()
            throw CancellationException("Download cancelled")
        }
        calls.add(call)
        try {
            return block()
        } catch (cancelledDownload: CancellationException) {
            throw cancelledDownload
        } catch (attempt: AttemptException) {
            throw attempt
        } catch (io: IOException) {
            if (cancelled.get()) throw CancellationException("Download cancelled")
            throw io
        } finally {
            calls.remove(call)
        }
    }
}

internal fun IOException.asAttempt(phase: String): AttemptException {
    val timedOut = isTransferTimeout(this)
    val detail = if (timedOut) {
        "The request timed out."
    } else {
        "The connection dropped (${javaClass.simpleName})."
    }
    return AttemptException(
        phase = phase,
        apiStatus = if (timedOut) "timeout" else "connection",
        message = detail,
    )
}

private fun isTransferTimeout(error: Throwable): Boolean {
    return generateSequence(error) { it.cause }.any { item ->
        item is SocketTimeoutException ||
            (item is InterruptedIOException && item.message?.contains("timeout", ignoreCase = true) == true)
    }
}
