package com.thorfeeds.catalog.net

import com.thorfeeds.catalog.core.Names
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Scriptable
import java.io.File
import java.net.URI
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

interface GofileTokenStore {
    fun read(): String?
    fun write(token: String)
    fun clear()
}

class FileGofileTokenStore(private val file: File) : GofileTokenStore {
    private val json = Json { ignoreUnknownKeys = true }

    override fun read(): String? {
        return try {
            val root = json.parseToJsonElement(file.readText()) as? JsonObject ?: return null
            (root["token"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    override fun write(token: String) {
        file.parentFile?.mkdirs()
        file.writeText("""{"version":1,"token":${jsonString(token)}}""")
    }

    override fun clear() {
        file.delete()
    }

    private fun jsonString(value: String): String {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}

class MemoryGofileTokenStore : GofileTokenStore {
    var token: String? = null
    override fun read(): String? = token
    override fun write(token: String) { this.token = token }
    override fun clear() { token = null }
}

/**
 * Loads Gofile's public wt script and reads the guest signing secret the same
 * way the site does: run generateWT once and keep the suffix after the probe prefix.
 */
object GofileWtSecret {
    private const val PROBE_USER_AGENT = "HydraGofileUserAgent"
    private const val PROBE_LANGUAGE = "HydraGofileLanguage"
    private const val PROBE_TOKEN = "HydraGofileToken"

    init {
        try {
            ContextFactory.initGlobal(Watchdog)
        } catch (_: IllegalStateException) {
            // A factory is already installed. The outer time limit still applies.
        }
    }

    fun scriptUrlFromHomepage(html: String, website: String = "https://gofile.io/"): String? {
        val match = SCRIPT.find(html) ?: return null
        val scriptUrl = try {
            URI(website).resolve(match.groupValues[1])
        } catch (_: Exception) {
            return null
        }
        val websiteUri = URI(website)
        val websiteHost = websiteUri.host?.lowercase().orEmpty()
        val host = scriptUrl.host?.lowercase().orEmpty()
        val onWebsite = host == websiteHost || host.endsWith(".$websiteHost")
        val scheme = websiteUri.scheme ?: "https"
        if (!scriptUrl.scheme.equals(scheme, ignoreCase = true) || !onWebsite) return null
        return scriptUrl.toString()
    }

    fun fallbackScriptUrls(website: String = "https://gofile.io"): List<String> {
        val root = website.trimEnd('/')
        return listOf("$root/js/wt.obf.js", "$root/dist/js/wt.obf.js")
    }

    fun extract(script: String, timeoutMs: Long = GofileResolver.CALL_TIMEOUT_MS): String {
        if (script.trimStart().startsWith("<")) {
            throw AttemptException(
                phase = DownloadPhases.WEBSITE_TOKEN,
                message = "Gofile token script was a web page, so this mirror was skipped.",
            )
        }
        val task = FutureTask { extractBlocking(script, timeoutMs) }
        val thread = Thread(task, "gofile-wt")
        thread.isDaemon = true
        thread.start()
        val deadline = System.currentTimeMillis() + timeoutMs
        try {
            while (true) {
                if (TransferControl.isCancelled()) {
                    thread.interrupt()
                    throw CancellationException("Download cancelled")
                }
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0L) break
                try {
                    return task.get(minOf(remaining, 200L), TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    // Poll again so cancel is not stuck behind the script.
                } catch (error: ExecutionException) {
                    throw scriptFailure(error.cause)
                }
            }
        } finally {
            if (!task.isDone) thread.interrupt()
        }
        throw AttemptException(
            phase = DownloadPhases.WEBSITE_TOKEN,
            apiStatus = "timeout",
            message = "Gofile website token script timed out, so this mirror was skipped.",
        )
    }

    private fun extractBlocking(script: String, timeoutMs: Long): String {
        val context = Context.enter()
        try {
            context.languageVersion = Context.VERSION_ES6
            context.optimizationLevel = -1
            context.putThreadLocal(Watchdog.DEADLINE, System.currentTimeMillis() + timeoutMs)
            val scope = context.initSafeStandardObjects()
            context.evaluateString(scope, SANDBOX, "gofile-wt-setup", 1, null)
            context.evaluateString(scope, script, "gofile-wt", 1, null)
            if (scope.get("generateWT", scope) == Scriptable.NOT_FOUND) {
                throw MirrorException("Gofile website token script did not define a generator")
            }
            context.evaluateString(scope, PROBE, "gofile-wt-probe", 1, null)
            val raw = scope.get("__raw", scope)?.toString().orEmpty()
            val prefix = "$PROBE_USER_AGENT::$PROBE_LANGUAGE::$PROBE_TOKEN::"
            if (!raw.startsWith(prefix)) throw MirrorException("Gofile token script format is unsupported")
            val secret = raw.removePrefix(prefix).split("::").drop(1).joinToString("::").trim()
            if (secret.isEmpty()) throw MirrorException("Gofile token script secret was empty")
            return secret
        } finally {
            Context.exit()
        }
    }

    private fun scriptFailure(cause: Throwable?): Throwable {
        val chain = generateSequence(cause) { it.cause }
        if (chain.any { it is ScriptTimeout }) {
            return AttemptException(
                phase = DownloadPhases.WEBSITE_TOKEN,
                apiStatus = "timeout",
                message = "Gofile website token script timed out, so this mirror was skipped.",
            )
        }
        return when (cause) {
            null -> AttemptException(
                phase = DownloadPhases.WEBSITE_TOKEN,
                message = "Gofile website token script did not define a generator, so this mirror was skipped.",
            )
            is AttemptException, is CancellationException -> cause
            is MirrorException -> AttemptException(
                phase = DownloadPhases.WEBSITE_TOKEN,
                message = cause.message ?: "Gofile token script could not be read, so this mirror was skipped.",
            )
            else -> AttemptException(
                phase = DownloadPhases.WEBSITE_TOKEN,
                message = cause.message ?: "Gofile token script could not be read, so this mirror was skipped.",
            )
        }
    }

    private class ScriptTimeout : Error("Gofile website token script timed out")

    private object Watchdog : ContextFactory() {
        val DEADLINE = Any()

        override fun makeContext(): Context {
            return super.makeContext().apply {
                optimizationLevel = -1
                instructionObserverThreshold = 10_000
            }
        }

        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            val deadline = cx.getThreadLocal(DEADLINE) as? Long
            val expired = deadline != null && System.currentTimeMillis() > deadline
            if (expired || TransferControl.isCancelled()) throw ScriptTimeout()
        }
    }

    private val SCRIPT = Regex("""<script[^>]+src=["']([^"']*wt[^"'/]*\.js)["']""", RegexOption.IGNORE_CASE)
    private const val SANDBOX = """
        var appdata = {};
        var navigator = { userAgent: 'HydraGofileUserAgent', language: 'HydraGofileLanguage' };
        var window = { crypto: {}, location: { hostname: 'gofile.io', search: '' }, navigator: navigator };
        var console = { error: function(){}, log: function(){}, warn: function(){} };
    """
    private val PROBE = """
        var __raw;
        this._sha256 = function(input) { __raw = String(input); return '${"0".repeat(64)}'; };
        generateWT('$PROBE_TOKEN');
    """
}

class GofileScriptSecret(
    private val pages: PageFetcher,
    private val callTimeoutMs: Long = GofileResolver.CALL_TIMEOUT_MS,
    private val website: String = "https://gofile.io/",
) {
    @Volatile private var cached: String? = null

    fun secret(): String {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            TransferControl.phase = DownloadPhases.WEBSITE_TOKEN
            val homeUrl = website
            var last: Exception? = null
            val discovered = try {
                val home = pages.get(
                    homeUrl,
                    mapOf(
                        "User-Agent" to GofileWebsiteToken.USER_AGENT,
                        "Accept" to "text/html,application/xhtml+xml",
                    ),
                    timeoutMs = callTimeoutMs,
                    phase = DownloadPhases.WEBSITE_TOKEN,
                )
                val challenged = home.code !in 200..299 ||
                    com.thorfeeds.catalog.core.FeedText.isCloudflareInterstitial(home.body)
                if (challenged) {
                    last = scriptFailure(homeUrl, home)
                    null
                } else {
                    GofileWtSecret.scriptUrlFromHomepage(home.body, homeUrl)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                last = explainScriptError(homeUrl, error)
                null
            }
            val urls = (listOfNotNull(discovered) + GofileWtSecret.fallbackScriptUrls(homeUrl)).distinct()
            for (url in urls) {
                if (TransferControl.isCancelled()) throw CancellationException("Download cancelled")
                try {
                    val body = pages.get(
                        url,
                        mapOf(
                            "User-Agent" to GofileWebsiteToken.USER_AGENT,
                            "Accept" to "application/javascript, text/javascript, */*;q=0.8",
                            "Referer" to homeUrl,
                        ),
                        timeoutMs = callTimeoutMs,
                        phase = DownloadPhases.WEBSITE_TOKEN,
                    )
                    val rejected = scriptFailure(url, body)
                    if (rejected != null) {
                        last = preferScriptFailure(last, rejected)
                        continue
                    }
                    val secret = GofileWtSecret.extract(body.body, callTimeoutMs)
                    cached = secret
                    return secret
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    last = preferScriptFailure(last, explainScriptError(url, error))
                }
            }
            val failure = last
            if (failure is AttemptException) throw failure
            throw AttemptException(
                phase = DownloadPhases.WEBSITE_TOKEN,
                message = "Gofile website token could not be loaded, so this mirror was skipped.\nURL: $homeUrl",
            )
        }
    }

    private fun explainScriptError(url: String, error: Exception): Exception {
        if (error is AttemptException && isBareLoadMessage(error.message)) {
            return AttemptException(
                phase = DownloadPhases.WEBSITE_TOKEN,
                httpStatus = error.httpStatus,
                apiStatus = error.apiStatus,
                message = "Gofile website token script did not define a generator, so this mirror was skipped.\nURL: $url",
            )
        }
        if (error is AttemptException) {
            val message = error.message.orEmpty()
            val withUrl = if (message.contains("URL:")) message else "$message\nURL: $url"
            return error.withMessage(withUrl)
        }
        val text = error.message.orEmpty()
        if (isBareLoadMessage(text)) {
            return AttemptException(
                phase = DownloadPhases.WEBSITE_TOKEN,
                message = "Gofile website token script did not define a generator, so this mirror was skipped.\nURL: $url",
            )
        }
        return AttemptException(
            phase = DownloadPhases.WEBSITE_TOKEN,
            message = (text.ifBlank { "Gofile website token script could not be read, so this mirror was skipped." }) + "\nURL: $url",
        )
    }

    private fun scriptFailure(url: String, page: PageBody): AttemptException? {
        val body = page.body.trimStart('\uFEFF').trimStart()
        val cloudflare = com.thorfeeds.catalog.core.FeedText.isCloudflareInterstitial(page.body)
        val html = body.startsWith("<")
        val failed = page.code !in 200..299
        val empty = body.isEmpty() || page.isFile
        if (!cloudflare && !html && !failed && !empty) return null
        val why = when {
            cloudflare -> "Gofile returned a Cloudflare check page instead of the website token script, so this mirror was skipped."
            html -> "Gofile returned HTML instead of the website token script, so this mirror was skipped."
            failed -> "Gofile website token script request failed, so this mirror was skipped."
            else -> "Gofile website token script body was empty, so this mirror was skipped."
        }
        val type = page.contentType?.takeIf { it.isNotBlank() }?.let { "\nContent-Type: $it" }.orEmpty()
        return AttemptException(
            phase = DownloadPhases.WEBSITE_TOKEN,
            httpStatus = page.code,
            apiStatus = if (cloudflare) "cloudflare" else null,
            message = "$why\nURL: $url$type",
        )
    }

    private fun preferScriptFailure(current: Exception?, next: Exception): Exception {
        if (current == null) return next
        return if (scriptFailureRank(next.message) >= scriptFailureRank(current.message)) next else current
    }

    private fun scriptFailureRank(message: String?): Int {
        val text = message.orEmpty()
        return when {
            "Cloudflare" in text -> 3
            "HTML" in text -> 2
            "did not define" in text -> 2
            else -> 1
        }
    }

    private fun isBareLoadMessage(message: String?): Boolean {
        val text = message.orEmpty()
        return "token script did not load" in text || "token script failed to load" in text
    }
}

class GofileAuthException(val status: String, val httpStatus: Int? = null) : Exception("Gofile guest token was rejected")

class GofileResolver(
    private val pages: PageFetcher,
    private val tokens: GofileTokenStore,
    private val secretSource: () -> String,
    private val userAgent: String = GofileWebsiteToken.USER_AGENT,
    private val clockSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
    private val callTimeoutMs: Long = CALL_TIMEOUT_MS,
    apiBase: String = "https://api.gofile.io",
) {
    private val apiRoot = apiBase.trimEnd('/')

    fun resolve(uri: String): ResolvedMirror {
        val id = contentId(uri) ?: throw MirrorException("Unrecognized Gofile link")
        val firstToken = authorize(forceRefresh = false)
        try {
            return resolved(id, firstToken, uri)
        } catch (error: GofileAuthException) {
            if (!isGuestAuthStatus(error.status)) throw error
            val refreshed = authorize(forceRefresh = true)
            try {
                return resolved(id, refreshed, uri)
            } catch (again: GofileAuthException) {
                val detail = if (again.status == "error-notPremium") {
                    "Gofile refused this file for a free guest account, so this mirror was skipped."
                } else {
                    "Gofile could not sign in as a guest, so this mirror was skipped."
                }
                throw AttemptException(
                    phase = DownloadPhases.CONTENTS,
                    httpStatus = again.httpStatus,
                    apiStatus = again.status,
                    message = detail,
                )
            }
        }
    }

    private fun resolved(id: String, accountToken: String, mirrorUri: String): ResolvedMirror {
        val link = findFile(id, accountToken, mutableSetOf())
            ?: throw AttemptException(
                phase = DownloadPhases.CONTENTS,
                message = "Gofile did not include a file link, so this mirror was skipped.",
            )
        val name = Names.fileNameFromUrl(link.url)
        val headers = downloadHeaders(accountToken)
        return ResolvedMirror(
            label = "Gofile",
            files = listOf(
                link.copy(
                    fileName = name,
                    sourceKey = Names.sourceKey(mirrorUri, name),
                    headers = headers,
                ),
            ),
        )
    }

    private fun findFile(id: String, accountToken: String, seen: MutableSet<String>): RemoteFile? {
        if (!seen.add(id)) return null
        var page = 1
        var totalPages = 1
        val folders = mutableListOf<String>()
        while (page <= totalPages) {
            val content = getPage(id, accountToken, page)
            totalPages = maxOf(totalPages, content.totalPages)
            if (content.type.equals("file", ignoreCase = true) && !content.link.isNullOrBlank()) {
                return RemoteFile(content.link, content.name ?: "download", content.size, "", emptyMap())
            }
            for (child in content.children) {
                if (!child.canAccess) continue
                if (child.type.equals("file", ignoreCase = true) && !child.link.isNullOrBlank()) {
                    return RemoteFile(child.link, child.name ?: "download", child.size, "", emptyMap())
                }
                if (child.type.equals("folder", ignoreCase = true) && child.id.isNotBlank()) folders += child.id
            }
            page += 1
        }
        for (folder in folders) {
            findFile(folder, accountToken, seen)?.let { return it }
        }
        return null
    }

    private fun getPage(id: String, accountToken: String, page: Int): GofileContentPage {
        val headers = contentHeaders(accountToken)
        TransferControl.phase = DownloadPhases.CONTENTS
        val url = "$apiRoot/contents/$id?page=$page&pageSize=1000&sortField=createTime&sortDirection=-1"
        val response = try {
            pages.get(url, headers, timeoutMs = callTimeoutMs, phase = DownloadPhases.CONTENTS)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: AttemptException) {
            throw error.withMessage(transportMessage(DownloadPhases.CONTENTS, error))
        }
        val content = GofileContents.parseContentPage(response.body)
            ?: throw AttemptException(
                phase = DownloadPhases.CONTENTS,
                httpStatus = response.code,
                message = "Gofile returned an unreadable catalog, so this mirror was skipped.",
            )
        if (content.status == "error-rateLimit" || response.code == 429) {
            throw AttemptException(
                phase = DownloadPhases.CONTENTS,
                httpStatus = response.code,
                apiStatus = content.status.ifBlank { "error-rateLimit" },
                message = "Gofile is busy, so this mirror was skipped.",
            )
        }
        if (isGuestAuthStatus(content.status)) throw GofileAuthException(content.status, response.code)
        if (content.status == "error-notFound") {
            throw AttemptException(
                phase = DownloadPhases.CONTENTS,
                httpStatus = response.code,
                apiStatus = "error-notFound",
                message = "Gofile could not find this file, so this mirror was skipped.",
            )
        }
        if (content.status == "error-passwordRequired" || content.status == "error-passwordWrong") {
            throw AttemptException(
                phase = DownloadPhases.CONTENTS,
                httpStatus = response.code,
                apiStatus = content.status,
                message = "Gofile password is not available, so this mirror was skipped.",
            )
        }
        if (!content.status.equals("ok", ignoreCase = true) || !content.canAccess) {
            throw AttemptException(
                phase = DownloadPhases.CONTENTS,
                httpStatus = response.code,
                apiStatus = content.status.ifBlank { null },
                message = "Gofile could not read this file, so this mirror was skipped.",
            )
        }
        return content
    }

    private fun authorize(forceRefresh: Boolean): String {
        synchronized(tokens) {
            if (!forceRefresh) {
                tokens.read()?.let { return it }
            } else {
                tokens.clear()
            }
            val token = createGuest()
            tokens.write(token)
            return token
        }
    }

    private fun createGuest(): String {
        var lastStatus = "no response"
        var lastCode: Int? = null
        repeat(3) { attempt ->
            if (TransferControl.isCancelled()) throw CancellationException("Download cancelled")
            val headers = createHeaders()
            TransferControl.phase = DownloadPhases.GUEST_TOKEN
            val response = try {
                pages.postEmpty(
                    "$apiRoot/accounts",
                    headers,
                    timeoutMs = callTimeoutMs,
                    phase = DownloadPhases.GUEST_TOKEN,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: AttemptException) {
                throw error.withMessage(transportMessage(DownloadPhases.GUEST_TOKEN, error))
            }
            lastCode = response.code
            val root = try {
                json.parseToJsonElement(response.body) as? JsonObject
            } catch (_: Exception) {
                null
            }
            val status = (root?.get("status") as? JsonPrimitive)?.contentOrNull.orEmpty()
            val token = ((root?.get("data") as? JsonObject)?.get("token") as? JsonPrimitive)?.contentOrNull
            if (status == "ok" && !token.isNullOrBlank()) return token
            lastStatus = status.ifBlank { response.code.toString() }
            val retry = attempt < 2 && (status == "error-rateLimit" || response.code in RETRYABLE)
            if (!retry) {
                throw AttemptException(
                    phase = DownloadPhases.GUEST_TOKEN,
                    httpStatus = response.code,
                    apiStatus = status.ifBlank { null },
                    message = "Gofile could not open a guest session, so this mirror was skipped.",
                )
            }
            pauseBeforeRetry(attempt)
        }
        throw AttemptException(
            phase = DownloadPhases.GUEST_TOKEN,
            httpStatus = lastCode,
            apiStatus = lastStatus,
            message = "Gofile could not open a guest session, so this mirror was skipped.",
        )
    }

    private fun pauseBeforeRetry(attempt: Int) {
        var left = (1L shl attempt) * 1000L
        while (left > 0) {
            if (TransferControl.isCancelled()) throw CancellationException("Download cancelled")
            val step = minOf(200L, left)
            Thread.sleep(step)
            left -= step
        }
    }

    private fun transportMessage(phase: String, error: AttemptException): String {
        val timedOut = error.apiStatus == "timeout"
        return when (phase) {
            DownloadPhases.GUEST_TOKEN -> if (timedOut) {
                "Gofile guest token request timed out, so this mirror was skipped."
            } else {
                "Gofile guest token connection dropped, so this mirror was skipped."
            }
            DownloadPhases.CONTENTS -> if (timedOut) {
                "Gofile contents lookup timed out, so this mirror was skipped."
            } else {
                "Gofile contents lookup connection dropped, so this mirror was skipped."
            }
            else -> error.message ?: "Gofile did not answer, so this mirror was skipped."
        }
    }

    private fun createHeaders(): Map<String, String> {
        return baseHeaders() + mapOf(
            "X-Website-Token" to websiteToken(""),
            "X-BL" to GofileWebsiteToken.LANGUAGE,
        )
    }

    private fun contentHeaders(accountToken: String): Map<String, String> {
        return baseHeaders(accountToken) + mapOf(
            "X-Website-Token" to websiteToken(accountToken),
            "X-BL" to GofileWebsiteToken.LANGUAGE,
        )
    }

    private fun downloadHeaders(accountToken: String): Map<String, String> {
        return baseHeaders(accountToken) + mapOf("Cookie" to "accountToken=$accountToken")
    }

    private fun baseHeaders(accountToken: String? = null): Map<String, String> {
        val headers = mutableMapOf(
            "User-Agent" to userAgent,
            "Accept" to "application/json",
            "Origin" to "https://gofile.io",
            "Referer" to "https://gofile.io/",
        )
        if (!accountToken.isNullOrBlank()) headers["Authorization"] = "Bearer $accountToken"
        return headers
    }

    private fun websiteToken(accountToken: String): String {
        val salt = try {
            secretSource()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: AttemptException) {
            throw error
        } catch (_: Exception) {
            throw AttemptException(
                phase = DownloadPhases.WEBSITE_TOKEN,
                message = "Gofile website token could not be loaded, so this mirror was skipped.",
            )
        }
        return GofileWebsiteToken.compute(
            userAgent = userAgent,
            language = GofileWebsiteToken.LANGUAGE,
            accountToken = accountToken,
            unixSeconds = clockSeconds(),
            salt = salt,
        )
    }

    private fun isGuestAuthStatus(status: String): Boolean {
        return status == "error-notPremium" || status == "error-wrongToken" || status == "error-notAuthenticated"
    }

    companion object {
        const val CALL_TIMEOUT_MS = 15_000L
        private val json = Json { ignoreUnknownKeys = true }
        private val RETRYABLE = setOf(408, 425, 429, 500, 502, 503, 504)

        fun contentId(uri: String): String? {
            if (com.thorfeeds.catalog.core.UriHosts.kind(uri) != com.thorfeeds.catalog.core.HostKind.GOFILE_SHARE) return null
            val path = com.thorfeeds.catalog.core.UriHosts.pathOf(uri).trimEnd('/')
            if (!path.startsWith("/d/")) return null
            val id = path.removePrefix("/d/")
            if (id.isBlank() || "/" in id) return null
            return id
        }
    }
}
