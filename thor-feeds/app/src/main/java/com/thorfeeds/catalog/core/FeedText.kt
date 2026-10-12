package com.thorfeeds.catalog.core

/**
 * Classifies a feed response body. A Cloudflare interstitial is reported as
 * such. This does not try to pass the check.
 */
object FeedText {
    fun decode(bytes: ByteArray): String {
        var start = 0
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            start = 3
        }
        return String(bytes, start, bytes.size - start, Charsets.UTF_8)
            .removePrefix("\uFEFF")
            .trim()
    }

    fun looksLikeJson(body: String): Boolean {
        val head = body.trimStart('\uFEFF').trimStart()
        return head.startsWith("{") || head.startsWith("[")
    }

    /**
     * @return a message when [body] is an interstitial or other HTML page,
     * or null when the body should be parsed as a feed.
     */
    fun blockMessage(body: String, httpStatus: Int? = null): String? {
        if (looksLikeJson(body)) return null
        val head = body.trimStart('\uFEFF').trimStart().take(2500).lowercase()
        if (head.isEmpty()) return null
        val status = httpStatus?.let { " (HTTP $it)" }.orEmpty()
        val cloudflare = "just a moment" in head ||
            "cf-browser-verification" in head ||
            "cf-challenge" in head ||
            "checking your browser" in head ||
            ("cloudflare" in head && ("challenge" in head || "attention required" in head))
        if (cloudflare) {
            return "The server returned a Cloudflare check page$status, not the JSON feed. " +
                "This app does not complete that check. Use a URL that returns the feed directly."
        }
        if (head.startsWith("<!doctype") || head.startsWith("<html") || head.startsWith("<head")) {
            return "The server returned a web page$status, not JSON."
        }
        return null
    }
}
