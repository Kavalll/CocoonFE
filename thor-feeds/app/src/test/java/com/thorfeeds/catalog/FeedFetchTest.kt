package com.thorfeeds.catalog

import com.thorfeeds.catalog.core.FeedChallengeException
import com.thorfeeds.catalog.core.FeedFormatException
import com.thorfeeds.catalog.core.FeedParser
import com.thorfeeds.catalog.core.FeedText
import com.thorfeeds.catalog.net.FeedFetcher
import com.thorfeeds.catalog.net.HttpIdentity
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedFetchTest {
    private val json = """
        {"name":"Shelf","downloads":[{"title":"Example Quest Free Download","uploadDate":"2026-10-11T19:02:09+00:00","fileSize":"31 GB","uris":["https://cdn.example/a"]}]}
    """.trim()

    @Test
    fun cloudflareInterstitialNamesTheCheckPageAndStatus() {
        val message = FeedText.blockMessage(
            "<!DOCTYPE html><html><head><title>Just a moment...</title></head><body>Checking your browser</body></html>",
            httpStatus = 403,
        )
        assertTrue(message.orEmpty().contains("Cloudflare"))
        assertTrue(message.orEmpty().contains("HTTP 403"))
        assertTrue(message.orEmpty().contains("Solve check"))
        assertFalse(message.orEmpty().contains("Couldn't read this feed"))
    }

    @Test
    fun webViewTextKeepsJsonAndIgnoresTheCheckPage() {
        val encoded = "\"{\\\"name\\\":\\\"Shelf\\\",\\\"downloads\\\":[]}\""
        val decoded = com.thorfeeds.catalog.core.WebFeedText.decodeJavascriptResult(encoded)
        assertTrue(decoded.contains("\"name\""))
        assertEquals(null, com.thorfeeds.catalog.core.WebFeedText.accept("<html>Just a moment...</html>"))
    }

    @Test
    fun browserFetchFollowsRedirectsAndGzip() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/final"))
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/plain")
                .setHeader("Content-Encoding", "gzip")
                .setBody(gzip(json)),
        )
        server.start()
        try {
            val client = OkHttpClient.Builder().followRedirects(true).followSslRedirects(true).build()
            val body = FeedFetcher(client).fetch(server.url("/collection").toString())
            val first = server.takeRequest()
            assertEquals(HttpIdentity.FEED_BROWSER_USER_AGENT, first.getHeader("User-Agent"))
            assertFalse(server.url("/collection").toString().endsWith(".json"))
            assertEquals("Shelf", FeedParser.parse(body).name)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun challengePageIsNotReportedAsAGenericReadFailure() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setHeader("Content-Type", "text/html")
                .setBody("<html><title>Just a moment...</title><body>cf-challenge</body></html>"),
        )
        server.start()
        try {
            FeedFetcher(OkHttpClient()).fetch(server.url("/feed").toString())
            throw AssertionError("Expected the Cloudflare page to fail the fetch")
        } catch (error: FeedChallengeException) {
            assertTrue(error.message.orEmpty().contains("Cloudflare"))
            assertTrue(error.message.orEmpty().contains("HTTP 503"))
            assertTrue(error.message.orEmpty().contains("Solve check"))
        } finally {
            server.shutdown()
        }
    }

    private fun gzip(text: String): Buffer {
        val buffer = Buffer()
        GzipSink(buffer).buffer().use { it.writeUtf8(text) }
        return buffer
    }
}
