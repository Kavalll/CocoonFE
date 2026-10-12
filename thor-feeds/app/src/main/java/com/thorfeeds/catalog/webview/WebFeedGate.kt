package com.thorfeeds.catalog.webview

import android.annotation.SuppressLint
import android.app.Activity
import android.content.MutableContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.thorfeeds.catalog.core.FeedChallengeException
import com.thorfeeds.catalog.core.FeedFormatException
import com.thorfeeds.catalog.core.FeedWebReader
import com.thorfeeds.catalog.core.SolveNavigation
import com.thorfeeds.catalog.core.WebFeedText
import com.thorfeeds.catalog.net.HttpIdentity
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * One WebView for a feed check. It stays alive until the body is JSON or the
 * user closes it. A challenge page is not loaded again.
 */
class WebFeedGate : FeedWebReader {
    private val main = Handler(Looper.getMainLooper())
    private var host: Activity? = null
    private var contextWrapper: MutableContextWrapper? = null
    private var visibleView: WebView? = null
    private var quietView: WebView? = null
    private var activeView: WebView? = null
    private var waiter: Waiter? = null
    private var requestedUrl: String? = null

    fun bind(activity: Activity) {
        host = activity
        contextWrapper?.baseContext = activity
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().flush()
    }

    fun unbind(activity: Activity) {
        if (host === activity) {
            contextWrapper?.baseContext = activity.applicationContext
            host = null
        }
        CookieManager.getInstance().flush()
        val view = visibleView
        (view?.parent as? ViewGroup)?.removeView(view)
        releaseQuiet()
    }

    fun interactiveWebView(activity: Activity): WebView {
        contextWrapper?.baseContext = activity
        visibleView?.let { return it }
        val wrapper = MutableContextWrapper(activity)
        contextWrapper = wrapper
        val view = WebView(wrapper)
        configure(view)
        visibleView = view
        return view
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(view: WebView) {
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(view, true)
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.databaseEnabled = true
        view.settings.cacheMode = WebSettings.LOAD_DEFAULT
        view.settings.setSupportMultipleWindows(false)
        view.settings.javaScriptCanOpenWindowsAutomatically = false
        view.settings.userAgentString = HttpIdentity.FEED_BROWSER_USER_AGENT
        view.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?,
            ): Boolean = false
        }
        view.webViewClient = object : WebViewClient() {
            @Deprecated("Kept so older WebView builds do not replace the challenge page")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = false

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false

            override fun onPageFinished(view: WebView, url: String?) {
                CookieManager.getInstance().flush()
                if (SolveNavigation.isChallenge(view.title, url)) return
                inspect(view)
            }
        }
    }

    fun detach(view: WebView) {
        if (activeView === view && waiter?.interactive == true) return
    }

    override suspend fun load(url: String, interactive: Boolean): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val view = if (interactive) {
                visibleView ?: host?.let { interactiveWebView(it) }
            } else if (waiter?.interactive == true) {
                null
            } else {
                quietWebView()
            }
            if (view == null) {
                continuation.resumeWithException(
                    FeedChallengeException(
                        "Finish Solve check on this feed. The check page is left open so it is not reloaded.",
                    ),
                )
                return@suspendCancellableCoroutine
            }
            activeView = view
            val previous = waiter
            previous?.timeout?.let(main::removeCallbacks)
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
                    if (waiter === mine) {
                        mine.timeout?.let(main::removeCallbacks)
                        waiter = null
                    }
                }
            }
            startLoadIfAllowed(view, url)
        }
    }

    private fun startLoadIfAllowed(view: WebView, url: String) {
        if (!SolveNavigation.shouldStartLoad(view.url, url, view.title)) {
            if (!SolveNavigation.isChallenge(view.title, view.url)) inspect(view)
            return
        }
        requestedUrl = url
        view.loadUrl(url)
    }

    fun cancelLoad() {
        waiter?.cancel()
        waiter = null
        requestedUrl = null
        activeView?.stopLoading()
        destroyVisible()
        releaseQuiet()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun quietWebView(): WebView? {
        if (waiter?.interactive == true) return null
        quietView?.let { return it }
        val activity = host ?: return null
        val view = WebView(activity)
        view.visibility = android.view.View.GONE
        view.setBackgroundColor(0)
        configure(view)
        (activity.window.decorView as ViewGroup).addView(view, ViewGroup.LayoutParams(1, 1))
        quietView = view
        return view
    }

    private fun releaseQuiet() {
        val view = quietView ?: return
        quietView = null
        if (activeView === view) activeView = null
        (view.parent as? ViewGroup)?.removeView(view)
        view.stopLoading()
        view.destroy()
    }

    private fun destroyVisible() {
        val view = visibleView ?: return
        visibleView = null
        if (activeView === view) activeView = null
        (view.parent as? ViewGroup)?.removeView(view)
        view.stopLoading()
        view.destroy()
    }

    private fun inspect(view: WebView) {
        val current = waiter ?: return
        if (SolveNavigation.isChallenge(view.title, view.url)) return
        view.evaluateJavascript(EXTRACT_TEXT) { raw ->
            if (waiter !== current) return@evaluateJavascript
            if (SolveNavigation.isChallenge(view.title, view.url)) return@evaluateJavascript
            val text = WebFeedText.decodeJavascriptResult(raw)
            val accepted = try {
                WebFeedText.accept(text)
            } catch (_: FeedFormatException) {
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
        requestedUrl = null
        if (!current.interactive) releaseQuiet()
        if (current.continuation.isActive) current.continuation.resume(body)
    }

    private fun fail(error: Exception) {
        val current = waiter ?: return
        current.timeout?.let(main::removeCallbacks)
        waiter = null
        if (!current.interactive) {
            activeView?.stopLoading()
            releaseQuiet()
        }
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
