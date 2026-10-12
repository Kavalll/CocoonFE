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

object SolveNavigation {
    fun isChallenge(title: String?, url: String?): Boolean {
        val titleText = title?.lowercase().orEmpty()
        val urlText = url?.lowercase().orEmpty()
        if ("just a moment" in titleText || "checking your browser" in titleText) return true
        if ("attention required" in titleText && "cloudflare" in titleText) return true
        return "cdn-cgi/challenge" in urlText || "__cf_chl" in urlText || "/cdn-cgi/" in urlText
    }

    /** False while the checkbox page is already up, so the app does not reload it. */
    fun shouldStartLoad(currentUrl: String?, requestedUrl: String, title: String?): Boolean {
        if (isChallenge(title, currentUrl)) return false
        if (currentUrl.isNullOrBlank() || currentUrl == "about:blank") return true
        return currentUrl.trimEnd('/') != requestedUrl.trimEnd('/')
    }
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
