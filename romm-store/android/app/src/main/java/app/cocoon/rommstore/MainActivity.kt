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
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
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
        Toast.makeText(this, "RomM Store ${appVersion()}", Toast.LENGTH_LONG).show()
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
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                val url = request.url.toString()
                if (url.contains("index-dSIPXYpo") || url.contains("assets/index-")) {
                    Log.e("RommStore", "stale hashed asset request $url")
                }
                return null
            }

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
        statusView.text = "Cocoon RomM Store ${appVersion()} — starting…"
        loadBundledUi()
        mainHandler.postDelayed({
            if (!uiReady) {
                showNativeError(
                    "Store ${appVersion()} loaded HTML but JavaScript did not start. Logcat filter RommStore must mention 1.0.5 and must not mention index-dSIPXYpo.js.",
                )
            }
        }, 4000)
    }

    @Suppress("DEPRECATION")
    private fun appVersion(): String {
        return runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "unknown"
    }

    private fun loadBundledUi() {
        val version = appVersion()
        val html = resources.openRawResource(R.raw.store).bufferedReader(Charsets.UTF_8).use { it.readText() }
        Log.e(
            "RommStore",
            "boot $version raw/store.html ${html.length} chars module=${html.contains("type=\"module\"")} oldBundle=${html.contains("index-dSIPXYpo")}",
        )
        if (html.isBlank()) {
            showNativeError("Store $version: res/raw/store.html is empty. Git pull and Clean Project.")
            return
        }
        if (
            html.contains("index-dSIPXYpo") ||
            html.contains("type=\"module\"") ||
            html.contains("type='module'") ||
            html.contains("assets/index-")
        ) {
            showNativeError(
                "Store $version still has the old Vite module files. In Android Studio: Git pull, Build → Clean Project, uninstall Cocoon RomM Store on the Thor, then Run.",
            )
            return
        }
        if (!html.contains("romm-store-build")) {
            showNativeError("Store $version HTML is missing the romm-store-build marker. Clean rebuild after git pull.")
            return
        }
        // Inject the HTML string so WebView cannot fall back to stale assets/www JS/CSS files.
        webView.loadDataWithBaseURL(
            "https://rommstore.local/store/",
            html,
            "text/html",
            "utf-8",
            null,
        )
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
                httpRequest: function (args) { return call("httpRequest", args); },
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
        fun httpRequest(id: String, args: String) {
            io.execute {
                var connection: HttpURLConnection? = null
                try {
                    val json = JSONObject(args)
                    val url = json.getString("url")
                    val method = json.optString("method", "GET").ifBlank { "GET" }
                    val body = json.optString("body")
                    val headers = json.optJSONObject("headers")
                    Log.e("RommStore", "native http $method $url")
                    connection = URL(url).openConnection() as HttpURLConnection
                    connection.requestMethod = method
                    connection.connectTimeout = 20000
                    connection.readTimeout = 60000
                    connection.instanceFollowRedirects = true
                    if (headers != null) {
                        val keys = headers.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            connection.setRequestProperty(key, headers.getString(key))
                        }
                    }
                    if (body.isNotEmpty() && method != "GET" && method != "HEAD") {
                        connection.doOutput = true
                        connection.outputStream.use { output ->
                            output.write(body.toByteArray(Charsets.UTF_8))
                        }
                    }
                    val code = connection.responseCode
                    val stream = if (code in 200..299) connection.inputStream else connection.errorStream ?: connection.inputStream
                    val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                    val contentType = connection.contentType ?: "application/json"
                    complete(
                        id,
                        true,
                        JSONObject()
                            .put("status", code)
                            .put("body", text)
                            .put("contentType", contentType)
                            .toString(),
                    )
                } catch (error: Exception) {
                    Log.e("RommStore", "native http failed", error)
                    complete(id, false, error.message ?: "http failed")
                } finally {
                    connection?.disconnect()
                }
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
