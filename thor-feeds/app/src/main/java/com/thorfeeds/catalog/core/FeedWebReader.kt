package com.thorfeeds.catalog.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reads a feed through the WebView network stack after the user has passed
 * a Cloudflare check. OkHttp is not used for that read.
 */
interface FeedWebReader {
    suspend fun load(url: String, interactive: Boolean): String
}

object WebFeedText {
    private val json = Json { ignoreUnknownKeys = true }

    fun decodeJavascriptResult(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return try {
            json.parseToJsonElement(raw).jsonPrimitive.contentOrNull ?: ""
        } catch (_: Exception) {
            raw.trim().trim('"')
        }
    }

    fun accept(text: String): String? {
        val cleaned = text.removePrefix("\uFEFF").trim()
        if (!FeedText.looksLikeJson(cleaned)) return null
        FeedParser.parse(cleaned)
        return cleaned
    }
}
