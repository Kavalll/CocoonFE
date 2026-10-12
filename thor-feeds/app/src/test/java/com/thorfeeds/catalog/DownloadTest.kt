package com.thorfeeds.catalog

import com.thorfeeds.catalog.core.DownloadPlan
import com.thorfeeds.catalog.core.Names
import com.thorfeeds.catalog.net.ByteSink
import com.thorfeeds.catalog.net.DownloadListener
import com.thorfeeds.catalog.net.ExistingPartial
import com.thorfeeds.catalog.net.FileFetcher
import com.thorfeeds.catalog.net.GofileContents
import com.thorfeeds.catalog.net.GofileResolver
import com.thorfeeds.catalog.net.GofileWebsiteToken
import com.thorfeeds.catalog.net.MirrorException
import com.thorfeeds.catalog.net.MirrorRouter
import com.thorfeeds.catalog.net.OkHttpFileFetcher
import com.thorfeeds.catalog.net.OneFichierResolver
import com.thorfeeds.catalog.net.PageFetcher
import com.thorfeeds.catalog.net.ReleaseDownloader
import com.thorfeeds.catalog.net.RemoteFile
import com.thorfeeds.catalog.net.ResolvedMirror
import com.thorfeeds.catalog.net.VikingFileResolver
import com.thorfeeds.catalog.net.DataNodesResolver
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream

class DownloadTest {
    @Test
    fun websiteTokenMatchesTheDocumentedFormula() {
        val token = GofileWebsiteToken.compute(
            userAgent = "TestAgent",
            language = "en-US",
            accountToken = "guest-token",
            unixSeconds = 1_700_000_000L,
            salt = GofileWebsiteToken.PRIMARY_SALT,
        )
        assertEquals("8cf366de2aceb62d22b7a1a7496d20bc6a2aad38dbdfab49a98fc2b62d8cd1cf", token)
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
    fun gofileResolverUsesContentsApiThenSharePage() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"status":"ok","data":{"token":"guest-token"}}"""))
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """{"status":"ok","data":{"type":"file","name":"harbor.zip","size":4,"link":"https://cdn.example/harbor.zip"}}""",
                    ),
            )
            val resolver = gofile(server)
            val resolved = resolver.resolve("https://gofile.io/d/example")
            server.takeRequest()
            val contents = server.takeRequest()
            assertEquals(
                "8cf366de2aceb62d22b7a1a7496d20bc6a2aad38dbdfab49a98fc2b62d8cd1cf",
                contents.getHeader("X-Website-Token"),
            )
            assertEquals("Bearer guest-token", contents.getHeader("Authorization"))
            assertEquals("https://cdn.example/harbor.zip", resolved.files.single().url)
            assertEquals("Gofile", resolved.label)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun gofileResolverFallsThroughWhenTheApiRefuses() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"status":"ok","data":{"token":"guest-token"}}"""))
            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"status":"error-notPremium","data":{}}"""),
            )
            server.enqueue(
                MockResponse().setHeader("Content-Type", "text/html")
                    .setBody("""<a href="https://store-9.gofile.io/download/web/example/harbor.zip">download</a>"""),
            )
            val resolved = gofile(server).resolve("https://gofile.io/d/example")
            assertTrue(resolved.files.single().url.contains("/download/web/example/harbor.zip"))
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
            } catch (error: MirrorException) {
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

    private fun gofile(server: MockWebServer): GofileResolver {
        val root = server.url("/").toString().trimEnd('/')
        return GofileResolver(
            pages = PageFetcher(OkHttpClient()),
            apiBase = root,
            siteBase = root,
            userAgent = "TestAgent",
            salts = listOf(GofileWebsiteToken.PRIMARY_SALT),
            clockSeconds = { 1_700_000_000L },
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

private class MemorySink : ByteSink {
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

    override fun open(partialName: String, append: Boolean): OutputStream {
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
