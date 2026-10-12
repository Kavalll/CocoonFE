package com.thorfeeds.catalog.webview

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.thorfeeds.catalog.core.FeedChallengeException
import com.thorfeeds.catalog.core.FeedFormatException
import com.thorfeeds.catalog.core.FeedText
import com.thorfeeds.catalog.core.FeedWebReader
import com.thorfeeds.catalog.core.WebFeedText
import com.thorfeeds.catalog.net.HttpIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellableContinuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Loads a feed URL inside a WebView so the user can pass a Cloudflare check
 * and the JSON is read by that same WebView. Clearance cookies stay in the
 * WebView cookie store. They are not copied onto OkHttp.
 */
class WebFeedGate : FeedWebReader {
    private val main = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var waiter: Waiter? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun attach(view: WebView) {
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(view, true)
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.userAgentString = HttpIdentity.FEED_BROWSER_USER_AGENT
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false

            override fun onPageFinished(view: WebView, url: String?) {
                inspect(view)
            }
        }
        webView = view
    }

    fun detach(view: WebView) {
        if (webView === view) webView = null
    }

    override suspend fun load(url: String, interactive: Boolean): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val view = webView
            if (view == null) {
                continuation.resumeWithException(FeedFormatException("Open Thor Feeds to refresh this feed."))
                return@suspendCancellableCoroutine
            }
            waiter?.cancel()
            val mine = Waiter(continuation, interactive)
            waiter = mine
            if (!interactive) {
                val timeout = Runnable {
                    if (waiter === mine) {
                        fail(FeedChallengeException(
                            "The server returned a Cloudflare check page, not the JSON feed. Tap Solve check and complete the page yourself.",
                        ))
                    }
                }
                mine.timeout = timeout
                main.postDelayed(timeout, QUIET_TIMEOUT_MS)
            }
            continuation.invokeOnCancellation {
                main.post {
                    if (waiter === mine) cancelLoad()
                }
            }
            view.loadUrl(url)
        }
    }

    fun cancelLoad() {
        waiter?.cancel()
        waiter = null
        webView?.stopLoading()
    }

    private fun inspect(view: WebView) {
        val current = waiter ?: return
        view.evaluateJavascript(EXTRACT_TEXT) { raw ->
            if (waiter !== current) return@evaluateJavascript
            val text = WebFeedText.decodeJavascriptResult(raw)
            val accepted = try {
                WebFeedText.accept(text)
            } catch (error: FeedFormatException) {
                if (FeedText.looksLikeJson(text)) {
                    fail(error)
                }
                null
            }
            if (accepted != null) {
                CookieManager.getInstance().flush()
                succeed(accepted)
            }
        }
    }

    private fun succeed(body: String) {
        val current = waiter ?: return
        current.timeout?.let(main::removeCallbacks)
        waiter = null
        if (current.continuation.isActive) current.continuation.resume(body)
    }

    private fun fail(error: Exception) {
        val current = waiter ?: return
        current.timeout?.let(main::removeCallbacks)
        waiter = null
        webView?.stopLoading()
        if (current.continuation.isActive) current.continuation.resumeWithException(error)
    }

    private class Waiter(
        val continuation: CancellableContinuation<String>,
        val interactive: Boolean,
        var timeout: Runnable? = null,
    ) {
        fun cancel() {
            timeout?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        }
    }

    companion object {
        private const val QUIET_TIMEOUT_MS = 20_000L
        private const val EXTRACT_TEXT = """
            (function() {
              var pre = document.querySelector('pre');
              if (pre && pre.innerText) return pre.innerText;
              if (document.body && document.body.innerText) return document.body.innerText;
              return '';
            })()
        """
    }
}
