package app.cocoon.rommstore

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var statusView: TextView
    private val io = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("rommstore", MODE_PRIVATE) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var uiReady = false

    private var pendingPickId: String? = null

    private val openTree = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val requestId = pendingPickId
        pendingPickId = null
        if (uri == null) {
            complete(requestId, false, "cancelled")
            return@registerForActivityResult
        }
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        prefs.edit().putString("romRoot", uri.toString()).apply()
        complete(requestId, true, uri.toString())
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.storeWebView)
        statusView = findViewById(R.id.storeStatus)
        webView.setBackgroundColor(Color.parseColor("#12141c"))

        @Suppress("DEPRECATION")
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            mediaPlaybackRequiresUserGesture = false
        }
        WebView.setWebContentsDebuggingEnabled(true)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                Log.e(
                    "RommStore",
                    "${consoleMessage.messageLevel()} ${consoleMessage.message()} (${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})",
                )
                return true
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                val message = "WebView error ${error.errorCode} ${error.description} ${request.url}"
                Log.e("RommStore", message)
                if (request.isForMainFrame) {
                    showNativeError(message)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                Log.e("RommStore", "page finished $url")
                injectBridge()
                view?.evaluateJavascript(
                    "(function(){var el=document.getElementById('app');return el?el.innerText.slice(0,240):'no #app';})()",
                ) { text ->
                    Log.e("RommStore", "app text=$text")
                    if (text != null && text != "\"no #app\"" && text != "null") {
                        statusView.visibility = View.GONE
                    }
                    if (text != null && (text.contains("Connect RomM") || text.contains("failed to start"))) {
                        markUiReady()
                    }
                }
            }
        }
        webView.addJavascriptInterface(Bridge(), "CocoonRommNative")
        loadBundledUi()
        mainHandler.postDelayed({
            if (!uiReady) {
                showNativeError(
                    "The store page did not start. In Android Studio Logcat, filter by RommStore and look for red lines.",
                )
            }
        }, 4000)
    }

    private fun loadBundledUi() {
        val html = assets.open("www/index.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
        Log.e("RommStore", "loading bundled UI (${html.length} chars, file:///android_asset/www/index.html)")
        if (html.isBlank()) {
            showNativeError("Bundled index.html is empty.")
            return
        }
        if (html.contains("type=\"module\"") || html.contains("type='module'")) {
            Log.e("RommStore", "bundled HTML still uses ES modules; WebView will stay blank")
        }
        webView.loadUrl("file:///android_asset/www/index.html")
    }

    private fun markUiReady() {
        uiReady = true
        statusView.visibility = View.GONE
    }

    private fun showNativeError(message: String) {
        statusView.visibility = View.VISIBLE
        statusView.text = message
    }

    private fun romRootUri(): Uri? = prefs.getString("romRoot", null)?.let(Uri::parse)

    private fun romRootDoc(): DocumentFile? {
        val uri = romRootUri() ?: return null
        return DocumentFile.fromTreeUri(this, uri)
    }

    private fun childDoc(root: DocumentFile, relativePath: String, create: Boolean): DocumentFile? {
        val parts = relativePath.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return root
        var current = root
        for ((index, part) in parts.withIndex()) {
            val last = index == parts.lastIndex
            val existing = current.findFile(part)
            current = when {
                existing != null -> existing
                !create -> return null
                last -> current.createFile("application/octet-stream", part) ?: return null
                else -> current.createDirectory(part) ?: return null
            }
        }
        return current
    }

    private fun complete(id: String?, ok: Boolean, payload: String) {
        if (id == null) return
        val json = JSONObject()
            .put("id", id)
            .put("ok", ok)
            .put("payload", payload)
            .toString()
        runOnUiThread {
            webView.evaluateJavascript(
                "window.__cocoonRommComplete && window.__cocoonRommComplete($json)",
                null,
            )
        }
    }

    private fun injectBridge() {
        webView.evaluateJavascript(
            """
            (function () {
              if (window.CocoonRomm) return;
              const pending = {};
              window.__cocoonRommComplete = function (msg) {
                const job = pending[msg.id];
                if (!job) return;
                delete pending[msg.id];
                if (msg.ok) job.resolve(msg.payload);
                else job.reject(new Error(msg.payload || "Native bridge failed"));
              };
              function call(method, args) {
                return new Promise(function (resolve, reject) {
                  const id = String(Date.now()) + Math.random().toString(16).slice(2);
                  pending[id] = { resolve: resolve, reject: reject };
                  CocoonRommNative[method](id, JSON.stringify(args || {}));
                });
              }
              window.CocoonRomm = {
                pickRomRoot: function () { return call("pickRomRoot", {}); },
                getRomRoot: function () { return call("getRomRoot", {}); },
                listFolders: function () {
                  return call("listFolders", {}).then(function (raw) { return JSON.parse(raw); });
                },
                download: function (url, relativePath, authorization) {
                  return call("download", { url: url, relativePath: relativePath, authorization: authorization || "" });
                },
                fileExists: function (relativePath) {
                  return call("fileExists", { relativePath: relativePath }).then(function (raw) { return raw === "true"; });
                }
              };
            })();
            """.trimIndent(),
            null,
        )
    }

    inner class Bridge {
        @JavascriptInterface
        fun uiReady() {
            runOnUiThread { markUiReady() }
        }

        @JavascriptInterface
        fun pickRomRoot(id: String, @Suppress("UNUSED_PARAMETER") args: String) {
            pendingPickId = id
            runOnUiThread { openTree.launch(romRootUri()) }
        }

        @JavascriptInterface
        fun getRomRoot(id: String, @Suppress("UNUSED_PARAMETER") args: String) {
            complete(id, true, romRootUri()?.toString() ?: "")
        }

        @JavascriptInterface
        fun listFolders(id: String, @Suppress("UNUSED_PARAMETER") args: String) {
            io.execute {
                val names = romRootDoc()?.listFiles()?.mapNotNull { file ->
                    file.name.takeIf { file.isDirectory }
                } ?: emptyList()
                complete(id, true, JSONArray(names).toString())
            }
        }

        @JavascriptInterface
        fun fileExists(id: String, args: String) {
            io.execute {
                val relative = JSONObject(args).optString("relativePath")
                val exists = romRootDoc()?.let { childDoc(it, relative, false)?.exists() } == true
                complete(id, true, if (exists) "true" else "false")
            }
        }

        @JavascriptInterface
        fun download(id: String, args: String) {
            io.execute {
                try {
                    val json = JSONObject(args)
                    val url = json.getString("url")
                    val relative = json.getString("relativePath")
                    val authorization = json.optString("authorization")
                    val root = romRootDoc() ?: throw IllegalStateException("Choose a ROM root folder first.")
                    val target = childDoc(root, relative, true) ?: throw IllegalStateException("Could not create $relative")
                    val connection = URL(url).openConnection() as HttpURLConnection
                    if (authorization.isNotBlank()) connection.setRequestProperty("Authorization", authorization)
                    connection.connectTimeout = 20000
                    connection.readTimeout = 0
                    connection.instanceFollowRedirects = true
                    try {
                        val code = connection.responseCode
                        if (code !in 200..299) {
                            throw IllegalStateException("Download failed ($code)")
                        }
                        connection.inputStream.use { input ->
                            writeToDoc(target, input)
                        }
                    } finally {
                        connection.disconnect()
                    }
                    complete(id, true, relative)
                } catch (error: Exception) {
                    complete(id, false, error.message ?: "download failed")
                }
            }
        }
    }

    private fun writeToDoc(target: DocumentFile, input: InputStream) {
        contentResolver.openOutputStream(target.uri, "w")?.use { output ->
            input.copyTo(output)
        } ?: throw IllegalStateException("Unable to open ${target.name}")
    }
}
