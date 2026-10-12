package com.thorfeeds.catalog

import com.thorfeeds.catalog.core.DownloadPlan
import com.thorfeeds.catalog.core.Names
import com.thorfeeds.catalog.net.AttemptException
import com.thorfeeds.catalog.net.AttemptReport
import com.thorfeeds.catalog.net.ByteSink
import com.thorfeeds.catalog.net.DownloadListener
import com.thorfeeds.catalog.net.DownloadPhases
import com.thorfeeds.catalog.net.ExistingPartial
import com.thorfeeds.catalog.net.FileFetcher
import com.thorfeeds.catalog.net.GofileContents
import com.thorfeeds.catalog.net.GofileResolver
import com.thorfeeds.catalog.net.GofileTokenStore
import com.thorfeeds.catalog.net.GofileWebsiteToken
import com.thorfeeds.catalog.net.GofileWtSecret
import com.thorfeeds.catalog.net.MemoryGofileTokenStore
import com.thorfeeds.catalog.net.MirrorException
import com.thorfeeds.catalog.net.MirrorRouter
import com.thorfeeds.catalog.net.OkHttpFileFetcher
import com.thorfeeds.catalog.net.OneFichierResolver
import com.thorfeeds.catalog.net.PageFetcher
import com.thorfeeds.catalog.net.ReleaseDownloader
import com.thorfeeds.catalog.net.RemoteFile
import com.thorfeeds.catalog.net.ResolvedMirror
import com.thorfeeds.catalog.net.TransferControl
import com.thorfeeds.catalog.net.VikingFileResolver
import com.thorfeeds.catalog.net.DataNodesResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit

class DownloadTest {
    @Before
    fun resetTransferControl() {
        TransferControl.reset()
    }

    @Test
    fun websiteTokenMatchesTheGuestFormula() {
        val token = GofileWebsiteToken.compute(
            userAgent = GofileWebsiteToken.USER_AGENT,
            language = "en-US",
            accountToken = "guest-token",
            unixSeconds = 1_700_000_000L,
            salt = "test-secret",
        )
        assertEquals("3f860122d8ea950a561032df6bec1933199732a5480cb374b7cdf07923f443ab", token)
    }

    @Test
    fun gofileScriptDiscoveryStaysOnTheGofileSite() {
        val html = """<html><script src="/js/wt.obf.js"></script><script src="https://evil.test/wt.js"></script></html>"""
        assertEquals("https://gofile.io/js/wt.obf.js", GofileWtSecret.scriptUrlFromHomepage(html))
        assertEquals(null, GofileWtSecret.scriptUrlFromHomepage("""<script src="http://gofile.io/js/wt.js"></script>"""))
        val secret = GofileWtSecret.extract(
            """
            function generateWT(token) {
              var slot = Math.floor(Date.now() / 1000 / 14400);
              return _sha256(navigator.userAgent + '::' + navigator.language + '::' + token + '::' + slot + '::' + 'unit-secret');
            }
            """.trimIndent(),
        )
        assertEquals("unit-secret", secret)
    }

    @Test(timeout = 10_000)
    fun websiteTokenScriptCannotHang() {
        try {
            GofileWtSecret.extract("function generateWT(token) { while (true) {} }", timeoutMs = 800)
            throw AssertionError("A hung website-token script should time out")
        } catch (error: AttemptException) {
            assertEquals(DownloadPhases.WEBSITE_TOKEN, error.phase)
            assertEquals("timeout", error.apiStatus)
            assertTrue(error.message.orEmpty().contains("timed out"))
        }
    }

    @Test
    fun failureReportNamesPhaseHostMirrorAndStatus() {
        val text = AttemptReport.format(
            host = "Gofile",
            mirrorIndex = 0,
            mirrorCount = 1,
            phase = DownloadPhases.CONTENTS,
            httpStatus = 401,
            apiStatus = "error-notPremium",
            detail = "Gofile refused this file for a free guest account, so this mirror was skipped.",
        )
        assertEquals(
            """
            Mirror 1 of 1 · Gofile
            Phase: contents lookup
            HTTP 401
            Status: error-notPremium
            Gofile refused this file for a free guest account, so this mirror was skipped.
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun gofileContentsParserReadsFolderFiles() {
        val listing = GofileContents.parse(
            """
            {"status":"ok","data":{"type":"folder","children":{
              "file-1":{"type":"file","name":"harbor.zip","size":12,"link":"https://cdn.example/harbor.zip"}
            }}}
            """.trimIndent(),
            mirrorUri = "https://gofile.io/d/example",
            headers = emptyMap(),
        )
        assertEquals("ok", listing?.status)
        assertEquals("harbor.zip", listing?.files?.single()?.fileName)
        assertEquals(12L, listing?.files?.single()?.sizeBytes)
        val missing = GofileContents.parse(
            """{"status":"error-notPremium","data":{}}""",
            "https://gofile.io/d/example",
            emptyMap(),
        )
        assertTrue(missing?.files?.isEmpty() == true)
        val fromPage = GofileContents.linksFromHtml(
            """<a href="https://store-9.gofile.io/download/web/example/harbor.zip">file</a>""",
            "https://gofile.io/d/example",
            emptyMap(),
        )
        assertEquals(1, fromPage.size)
    }

    @Test
    fun gofileGuestAccountSignsTheContentsRequest() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"status":"ok","data":{"token":"guest-token"}}"""))
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """{"status":"ok","data":{"type":"file","name":"harbor.zip","size":4,"link":"https://cdn.example/harbor.zip"},"metadata":{"totalPages":1}}""",
                    ),
            )
            val resolved = gofile(server).resolve("https://gofile.io/d/example")
            val created = server.takeRequest()
            assertEquals("/accounts", created.path)
            assertEquals("en-US", created.getHeader("X-BL"))
            assertEquals(websiteToken(""), created.getHeader("X-Website-Token"))
            val contents = server.takeRequest()
            assertTrue(contents.path!!.startsWith("/contents/example"))
            assertTrue(contents.path!!.contains("pageSize=1000"))
            assertEquals("Bearer guest-token", contents.getHeader("Authorization"))
            assertEquals(websiteToken("guest-token"), contents.getHeader("X-Website-Token"))
            assertEquals("https://cdn.example/harbor.zip", resolved.files.single().url)
            assertTrue(resolved.files.single().headers["Cookie"].orEmpty().contains("accountToken=guest-token"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun gofileNotPremiumRefreshesTheGuestTokenOnce() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"status":"ok","data":{"token":"stale-token"}}"""))
            server.enqueue(MockResponse().setBody("""{"status":"error-notPremium","data":{}}"""))
            server.enqueue(MockResponse().setBody("""{"status":"ok","data":{"token":"fresh-token"}}"""))
            server.enqueue(MockResponse().setBody("""{"status":"error-notPremium","data":{}}"""))
            try {
                gofile(server).resolve("https://gofile.io/d/example")
                throw AssertionError("A second guest rejection should skip the mirror")
            } catch (error: AttemptException) {
                assertEquals(DownloadPhases.CONTENTS, error.phase)
                assertEquals("error-notPremium", error.apiStatus)
                assertEquals(200, error.httpStatus)
                assertTrue(error.message.orEmpty().contains("Gofile"))
                assertTrue(error.message.orEmpty().contains("guest"))
                assertFalse(error.message.orEmpty().contains("error-notPremium"))
                val shown = AttemptReport.format(
                    host = "Gofile",
                    mirrorIndex = 0,
                    mirrorCount = 1,
                    phase = error.phase,
                    httpStatus = error.httpStatus,
                    apiStatus = error.apiStatus,
                    detail = error.message.orEmpty(),
                )
                assertTrue(shown.contains("Status: error-notPremium"))
                assertTrue(shown.contains("Phase: contents lookup"))
                assertTrue(shown.contains("Mirror 1 of 1"))
            }
            assertEquals(4, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun gofileReusesACachedGuestToken() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse().setBody(
                    """{"status":"ok","data":{"type":"file","name":"harbor.zip","link":"https://cdn.example/harbor.zip"}}""",
                ),
            )
            val store = MemoryGofileTokenStore().apply { write("cached-token") }
            val resolved = gofile(server, store).resolve("https://gofile.io/d/example")
            val contents = server.takeRequest()
            assertEquals("Bearer cached-token", contents.getHeader("Authorization"))
            assertEquals(1, server.requestCount)
            assertEquals("https://cdn.example/harbor.zip", resolved.files.single().url)
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 15_000)
    fun gofileGuestCallTimesOut() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        try {
            try {
                gofile(server, callTimeoutMs = 700).resolve("https://gofile.io/d/example")
                throw AssertionError("A stalled guest call should fail the mirror")
            } catch (error: AttemptException) {
                assertEquals(DownloadPhases.GUEST_TOKEN, error.phase)
                assertEquals("timeout", error.apiStatus)
                assertTrue(error.message.orEmpty().contains("timed out"))
                assertTrue(error.message.orEmpty().contains("guest"))
            }
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 15_000)
    fun cancelAbortsGofileBeforeAnyFileBytes() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        try {
            val resolver = gofile(server, callTimeoutMs = 30_000)
            var thrown: Throwable? = null
            val worker = Thread {
                try {
                    resolver.resolve("https://gofile.io/d/example")
                } catch (error: Throwable) {
                    thrown = error
                }
            }
            worker.isDaemon = true
            worker.start()
            Thread.sleep(300)
            TransferControl.cancel()
            worker.join(5_000)
            assertFalse("cancel left the Gofile call running", worker.isAlive)
            assertTrue(thrown is CancellationException)
        } finally {
            server.shutdown()
            TransferControl.reset()
        }
    }

    @Test
    fun gofileWithoutAFileLinkFailsTheMirror() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"status":"ok","data":{"token":"guest-token"}}"""))
            server.enqueue(
                MockResponse().setBody(
                    """{"status":"ok","data":{"type":"folder","children":{}},"metadata":{"totalPages":1}}""",
                ),
            )
            try {
                gofile(server).resolve("https://gofile.io/d/example")
                throw AssertionError("A folder with no file should fail the mirror")
            } catch (error: AttemptException) {
                assertEquals(DownloadPhases.CONTENTS, error.phase)
                assertTrue(error.message.orEmpty().contains("file link"))
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun oneFichierPostsTheDownloadFormOnce() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse().setHeader("Content-Type", "text/html").setBody(
                    """
                    <form method="post" action="/gate">
                      <input type="hidden" name="adz" value="token">
                      <input type="submit" name="submit" value="Download">
                    </form>
                    """.trimIndent(),
                ),
            )
            server.enqueue(
                MockResponse().setHeader("Content-Type", "text/html").setBody(
                    """<a href="https://o-1.1fichier.com/not-a-real-file/demo.zip">Click here to download the file</a>""",
                ),
            )
            val pages = PageFetcher(OkHttpClient())
            val resolved = OneFichierResolver(pages).resolve(server.url("/share").toString())
            assertEquals("https://o-1.1fichier.com/not-a-real-file/demo.zip", resolved.files.single().url)
            server.takeRequest()
            val posted = server.takeRequest()
            assertEquals("/gate", posted.path)
            assertTrue(posted.body.readUtf8().contains("adz=token"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun vikingFileSkipsInteractiveChecksAndReadsEmbeddedLinks() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse().setHeader("Content-Type", "text/html")
                    .setBody("""<div id="captcha-download" class="cf-turnstile"></div>"""),
            )
            val pages = PageFetcher(OkHttpClient())
            try {
                VikingFileResolver(pages).resolve(server.url("/f/example").toString())
                throw AssertionError("Interactive VikingFile pages should fail the mirror")
            } catch (error: MirrorException) {
                assertTrue(error.message.orEmpty().contains("interactive"))
            }
            server.enqueue(
                MockResponse().setHeader("Content-Type", "text/html")
                    .setBody("""{"link":"https://cdn.example/demo.zip"}"""),
            )
            val resolved = VikingFileResolver(pages).resolve(server.url("/f/example").toString())
            assertEquals("https://cdn.example/demo.zip", resolved.files.single().url)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun dataNodesFollowsItsDownloadForm() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse().setHeader("Content-Type", "text/html").setBody(
                    """
                    <form method="post" action="/continue">
                      <input type="hidden" name="op" value="download2">
                      <input type="hidden" name="id" value="demo">
                      <input type="submit" name="method_free" value="Free Download">
                    </form>
                    """.trimIndent(),
                ),
            )
            server.enqueue(
                MockResponse().setHeader("Content-Type", "text/html").setBody(
                    """<a href="https://n1.datanodes.to/d/not-a-real-file.bin">download</a>""",
                ),
            )
            val resolved = DataNodesResolver(PageFetcher(OkHttpClient())).resolve(server.url("/demo").toString())
            assertEquals("https://n1.datanodes.to/d/not-a-real-file.bin", resolved.files.single().url)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun downloaderTriesTheNextMirrorAfterAFailure() = runBlocking {
        val router = ScriptedRouter(
            mapOf(
                "https://cdn.example/fail" to Result.failure(MirrorException("nope")),
                "https://cdn.example/ok" to Result.success(
                    ResolvedMirror(
                        label = "Example",
                        files = listOf(remote("https://cdn.example/ok", "demo.bin")),
                    ),
                ),
            ),
        )
        val fetcher = RecordingFetcher()
        val events = mutableListOf<String>()
        ReleaseDownloader(router, fetcher).download(
            listOf("https://cdn.example/fail", "https://cdn.example/ok"),
            MemorySink(),
            object : DownloadListener {
                override fun onMirrorStarting(index: Int, count: Int, label: String) {
                    events += "start:$index:$label"
                }
                override fun onMirrorFailed(index: Int, message: String) {
                    events += "fail:$index"
                }
                override fun onProgress(fileName: String, bytesRead: Long, totalBytes: Long?) = Unit
            },
        )
        assertEquals(listOf("https://cdn.example/fail", "https://cdn.example/ok"), router.calls)
        assertEquals(listOf("demo.bin"), fetcher.names)
        assertEquals(listOf("start:0:cdn.example", "fail:0", "start:1:cdn.example"), events)
    }

    @Test
    fun downloaderKeepsEarlierMirrorFailures() = runBlocking {
        val router = ScriptedRouter(
            mapOf(
                "https://gofile.io/d/one" to Result.failure(
                    AttemptException(
                        phase = DownloadPhases.CONTENTS,
                        httpStatus = 401,
                        apiStatus = "error-notPremium",
                        message = "Gofile refused this file for a free guest account, so this mirror was skipped.",
                    ),
                ),
                "https://1fichier.com/two" to Result.failure(
                    AttemptException(
                        phase = DownloadPhases.CONNECTING,
                        httpStatus = 404,
                        apiStatus = null,
                        message = "1fichier did not return a direct file",
                    ),
                ),
            ),
        )
        var shown = ""
        try {
            ReleaseDownloader(router, RecordingFetcher()).download(
                listOf("https://gofile.io/d/one", "https://1fichier.com/two"),
                MemorySink(),
                object : DownloadListener {
                    override fun onMirrorStarting(index: Int, count: Int, label: String) = Unit
                    override fun onMirrorFailed(index: Int, message: String) {
                        shown = message
                    }
                    override fun onProgress(fileName: String, bytesRead: Long, totalBytes: Long?) = Unit
                },
            )
            throw AssertionError("both mirrors should fail")
        } catch (error: MirrorException) {
            assertEquals(shown, error.message)
        }
        assertTrue(shown.contains("Mirror 1 of 2 · Gofile"))
        assertTrue(shown.contains("Phase: contents lookup"))
        assertTrue(shown.contains("HTTP 401"))
        assertTrue(shown.contains("Status: error-notPremium"))
        assertTrue(shown.contains("Mirror 2 of 2 · 1fichier"))
        assertTrue(shown.contains("Phase: connecting"))
        assertTrue(shown.contains("HTTP 404"))
        assertFalse(shown.contains("download failed"))
    }

    @Test
    fun downloadResumesOnlyAfterTheServerAcceptsRanges() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return if (request.getHeader("Range") == "bytes=3-") {
                    MockResponse()
                        .setResponseCode(206)
                        .addHeader("Content-Range", "bytes 3-4/5")
                        .addHeader("Accept-Ranges", "bytes")
                        .setBody("lo")
                } else {
                    MockResponse().setResponseCode(200).setBody("HELLO").addHeader("Accept-Ranges", "bytes")
                }
            }
        }
        server.start()
        try {
            val client = OkHttpClient()
            val fetcher = OkHttpFileFetcher(client)
            val file = remote(server.url("/demo.bin").toString(), "demo.bin")
            val sink = MemorySink()
            sink.seed("demo.bin.partial", "hel".toByteArray(), file.sourceKey, acceptsRange = true)
            fetcher.fetch(file, sink) { _, _ -> }
            assertEquals("hello", sink.committed?.toString(Charsets.UTF_8))

            val restart = MemorySink()
            val noRange = MockWebServer()
            noRange.enqueue(MockResponse().setBody("hello"))
            noRange.start()
            try {
                val full = remote(noRange.url("/demo.bin").toString(), "demo.bin")
                restart.seed("demo.bin.partial", "hel".toByteArray(), full.sourceKey, acceptsRange = false)
                OkHttpFileFetcher(client).fetch(full, restart) { _, _ -> }
                assertEquals("hello", restart.committed?.toString(Charsets.UTF_8))
                assertFalse(noRange.takeRequest().headers.names().contains("Range"))
            } finally {
                noRange.shutdown()
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun htmlBodyIsNotSavedAsAGame() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/html").setBody("<html><body>nope</body></html>"),
        )
        server.start()
        try {
            val sink = MemorySink()
            try {
                OkHttpFileFetcher(OkHttpClient()).fetch(
                    remote(server.url("/page").toString(), "page.bin"),
                    sink,
                ) { _, _ -> }
                throw AssertionError("HTML responses are mirror failures")
            } catch (error: AttemptException) {
                assertEquals(DownloadPhases.TRANSFERRING, error.phase)
                assertEquals(200, error.httpStatus)
                assertTrue(error.message.orEmpty().contains("web page"))
            }
            assertTrue(sink.discarded)
            assertEquals(
                DownloadPlan.BodyMode.REJECT,
                DownloadPlan.interpret(sentRange = false, httpCode = 200, contentType = "text/html", htmlPrefix = false),
            )
            assertEquals(
                DownloadPlan.BodyMode.APPEND,
                DownloadPlan.interpret(sentRange = true, httpCode = 206, contentType = "application/octet-stream", htmlPrefix = false),
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun writeFailureNamesTheWritingPhase() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("hello"))
        server.start()
        try {
            val sink = object : MemorySink() {
                override fun open(partialName: String, append: Boolean): OutputStream {
                    return object : OutputStream() {
                        override fun write(b: Int) = throw IOException("disk full")
                        override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("disk full")
                    }
                }
            }
            try {
                OkHttpFileFetcher(OkHttpClient()).fetch(
                    remote(server.url("/a.bin").toString(), "a.bin"),
                    sink,
                ) { _, _ -> }
                throw AssertionError("a write error should fail the attempt")
            } catch (error: AttemptException) {
                assertEquals(DownloadPhases.WRITING, error.phase)
                assertTrue(error.message.orEmpty().contains("disk full"))
            }
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 15_000)
    fun cancelAbortsFileTransferBeforeBytes() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        try {
            val client = OkHttpClient.Builder()
                .callTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .connectTimeout(30, TimeUnit.SECONDS)
                .build()
            val job = launch(Dispatchers.IO) {
                OkHttpFileFetcher(client).fetch(
                    remote(server.url("/file.bin").toString(), "file.bin"),
                    MemorySink(),
                ) { _, _ -> }
            }
            delay(300)
            TransferControl.cancel()
            withTimeout(5_000) { job.join() }
            assertTrue(job.isCompleted)
        } finally {
            server.shutdown()
            TransferControl.reset()
        }
    }

    private fun gofile(
        server: MockWebServer,
        store: GofileTokenStore = MemoryGofileTokenStore(),
        callTimeoutMs: Long = GofileResolver.CALL_TIMEOUT_MS,
    ): GofileResolver {
        val root = server.url("/").toString().trimEnd('/')
        return GofileResolver(
            pages = PageFetcher(OkHttpClient()),
            tokens = store,
            secretSource = { "test-secret" },
            userAgent = GofileWebsiteToken.USER_AGENT,
            clockSeconds = { 1_700_000_000L },
            callTimeoutMs = callTimeoutMs,
            apiBase = root,
        )
    }

    private fun websiteToken(accountToken: String): String {
        return GofileWebsiteToken.compute(
            userAgent = GofileWebsiteToken.USER_AGENT,
            language = "en-US",
            accountToken = accountToken,
            unixSeconds = 1_700_000_000L,
            salt = "test-secret",
        )
    }

    private fun remote(url: String, name: String): RemoteFile {
        return RemoteFile(
            url = url,
            fileName = name,
            sizeBytes = null,
            sourceKey = Names.sourceKey(url, name),
        )
    }
}

private class ScriptedRouter(private val behavior: Map<String, Result<ResolvedMirror>>) : MirrorRouter {
    val calls = mutableListOf<String>()
    override suspend fun resolve(uri: String): ResolvedMirror {
        calls += uri
        return behavior.getValue(uri).getOrThrow()
    }
}

private class RecordingFetcher : FileFetcher {
    val names = mutableListOf<String>()
    override suspend fun fetch(
        file: RemoteFile,
        sink: ByteSink,
        onProgress: (Long, Long?) -> Unit,
    ): String {
        names += file.fileName
        onProgress(4, 4)
        return file.fileName
    }
}

private open class MemorySink : ByteSink {
    private val files = mutableMapOf<String, ByteArray>()
    private val metas = mutableMapOf<String, Pair<String, Boolean>>()
    var committed: ByteArray? = null
    var discarded = false

    fun seed(partialName: String, bytes: ByteArray, sourceKey: String, acceptsRange: Boolean) {
        files[partialName] = bytes
        metas[partialName] = sourceKey to acceptsRange
    }

    override fun existing(partialName: String, sourceKey: String): ExistingPartial {
        val meta = metas[partialName]
        val bytes = files[partialName]
        if (meta == null || bytes == null || meta.first != sourceKey || !meta.second) return ExistingPartial(0, false)
        return ExistingPartial(bytes.size.toLong(), true)
    }

    open override fun open(partialName: String, append: Boolean): OutputStream {
        val prefix = if (append) files[partialName] ?: ByteArray(0) else ByteArray(0)
        return object : ByteArrayOutputStream() {
            override fun close() {
                super.close()
                files[partialName] = prefix + toByteArray()
            }
        }
    }

    override fun writeMeta(partialName: String, sourceKey: String, acceptsRange: Boolean) {
        metas[partialName] = sourceKey to acceptsRange
    }

    override fun commit(partialName: String) {
        committed = files[partialName]
        files.remove(partialName)
    }

    override fun discard(partialName: String) {
        discarded = true
        files.remove(partialName)
        metas.remove(partialName)
    }
}
