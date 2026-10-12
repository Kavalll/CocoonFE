package com.thorfeeds.catalog.core

object DownloadPlan {
    fun rangeHeader(existingBytes: Long, acceptsRange: Boolean): String? {
        if (existingBytes > 0 && acceptsRange) return "bytes=$existingBytes-"
        return null
    }

    enum class BodyMode { APPEND, REPLACE, REJECT }

    fun interpret(sentRange: Boolean, httpCode: Int, contentType: String?, htmlPrefix: Boolean): BodyMode {
        if (htmlPrefix) return BodyMode.REJECT
        val type = contentType?.lowercase().orEmpty()
        if (type.contains("text/html") || type.contains("application/xhtml")) return BodyMode.REJECT
        return when {
            sentRange && httpCode == 206 -> BodyMode.APPEND
            httpCode == 200 -> BodyMode.REPLACE
            sentRange && httpCode == 416 -> BodyMode.REPLACE
            else -> BodyMode.REJECT
        }
    }

    fun totalBytes(sentRange: Boolean, existing: Long, contentLength: Long, contentRange: String?): Long? {
        if (!contentRange.isNullOrBlank()) {
            val total = Regex("""/(\d+)\s*$""").find(contentRange)?.groupValues?.get(1)?.toLongOrNull()
            if (total != null && total >= 0) return total
        }
        if (contentLength >= 0) {
            return if (sentRange) existing + contentLength else contentLength
        }
        return null
    }

    fun looksLikeHtmlPrefix(bytes: ByteArray, length: Int): Boolean {
        if (length <= 0) return false
        val sample = bytes.decodeToString(0, minOf(length, 64)).trimStart().lowercase()
        return sample.startsWith("<!doctype html") ||
            sample.startsWith("<html") ||
            sample.startsWith("<head")
    }
}
