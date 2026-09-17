package app.cocoon.rommstore

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
    private val io = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("rommstore", MODE_PRIVATE) }

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
        webView = WebView(this)
        setContentView(webView)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                injectBridge()
            }
        }
        webView.addJavascriptInterface(Bridge(), "CocoonRommNative")
        webView.loadUrl("file:///android_asset/www/index.html")
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
