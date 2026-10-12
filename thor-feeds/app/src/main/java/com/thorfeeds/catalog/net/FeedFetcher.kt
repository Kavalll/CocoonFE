package com.thorfeeds.catalog.net

import com.thorfeeds.catalog.core.FeedChallengeException
import com.thorfeeds.catalog.core.FeedFormatException
import com.thorfeeds.catalog.core.FeedText
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Loads a user-supplied feed URL. Redirects are followed by [http].
 * Gzip responses are decoded by OkHttp. A Cloudflare check page is returned
 * as an error, not solved.
 */
class FeedFetcher(private val http: OkHttpClient) {
    fun fetch(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", HttpIdentity.FEED_BROWSER_USER_AGENT)
            .header("Accept", "application/json, text/plain, */*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        http.newCall(request).execute().use { response ->
            val bytes = response.peekBody(MAX_FEED_BYTES + 1).bytes()
            if (bytes.size > MAX_FEED_BYTES) {
                throw FeedFormatException("That feed is too large to import.")
            }
            val text = FeedText.decode(bytes)
            FeedText.blockMessage(text, response.code)?.let { message ->
                if (FeedText.isCloudflareInterstitial(text)) throw FeedChallengeException(message)
                throw FeedFormatException(message)
            }
            if (response.code == 404) throw FeedFormatException("The feed URL was not found (HTTP 404).")
            if (!response.isSuccessful) {
                throw FeedFormatException("The feed server returned HTTP ${response.code}, not a JSON feed.")
            }
            return text
        }
    }

    companion object {
        const val MAX_FEED_BYTES = 48L * 1024L * 1024L
    }
}
