package com.thorfeeds.catalog.net

import com.thorfeeds.catalog.core.Names
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import java.io.File
import java.net.URI

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

    fun scriptUrlFromHomepage(html: String, website: String = "https://gofile.io/"): String? {
        val match = SCRIPT.find(html) ?: return null
        val scriptUrl = try {
            URI(website).resolve(match.groupValues[1])
        } catch (_: Exception) {
            return null
        }
        val websiteHost = URI(website).host?.lowercase().orEmpty()
        val host = scriptUrl.host?.lowercase().orEmpty()
        val onWebsite = host == websiteHost || host.endsWith(".$websiteHost")
        if (!scriptUrl.scheme.equals("https", ignoreCase = true) || !onWebsite) return null
        return scriptUrl.toString()
    }

    fun fallbackScriptUrls(website: String = "https://gofile.io"): List<String> {
        val root = website.trimEnd('/')
        return listOf("$root/js/wt.obf.js", "$root/dist/js/wt.obf.js")
    }

    fun extract(script: String): String {
        if (script.trimStart().startsWith("<")) {
            throw MirrorException("Gofile token script was a web page")
        }
        val context = Context.enter()
        try {
            context.languageVersion = Context.VERSION_ES6
            val scope = context.initSafeStandardObjects()
            context.evaluateString(scope, SANDBOX, "gofile-wt-setup", 1, null)
            context.evaluateString(scope, script, "gofile-wt", 1, null)
            if (scope.get("generateWT", scope) == Scriptable.NOT_FOUND) {
                throw MirrorException("Gofile token script did not load")
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

class GofileScriptSecret(private val pages: PageFetcher) {
    @Volatile private var cached: String? = null

    fun secret(): String {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val discovered = try {
                val home = pages.get(
                    "https://gofile.io/",
                    mapOf("User-Agent" to GofileWebsiteToken.USER_AGENT, "Accept" to "text/html"),
                )
                if (home.code in 200..299) GofileWtSecret.scriptUrlFromHomepage(home.body) else null
            } catch (_: Exception) {
                null
            }
            val urls = (listOfNotNull(discovered) + GofileWtSecret.fallbackScriptUrls()).distinct()
            var last: Exception? = null
            for (url in urls) {
                try {
                    val body = pages.get(
                        url,
                        mapOf(
                            "User-Agent" to GofileWebsiteToken.USER_AGENT,
                            "Accept" to "application/javascript, text/javascript, */*;q=0.8",
                            "Referer" to "https://gofile.io/",
                        ),
                    )
                    if (body.code !in 200..299 || body.body.trimStart().startsWith("<")) continue
                    val secret = GofileWtSecret.extract(body.body)
                    cached = secret
                    return secret
                } catch (error: Exception) {
                    last = error
                }
            }
            throw last ?: MirrorException("Gofile token script could not be loaded")
        }
    }
}

class GofileAuthException(val status: String) : Exception("Gofile guest token was rejected")

class GofileResolver(
    private val pages: PageFetcher,
    private val tokens: GofileTokenStore,
    private val secretSource: () -> String,
    private val userAgent: String = GofileWebsiteToken.USER_AGENT,
    private val clockSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
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
                if (again.status == "error-notPremium") {
                    throw MirrorException("Gofile refused this file for a free guest account, so this mirror was skipped.")
                }
                throw MirrorException("Gofile could not sign in as a guest, so this mirror was skipped.")
            }
        }
    }

    private fun resolved(id: String, accountToken: String, mirrorUri: String): ResolvedMirror {
        val link = findFile(id, accountToken, mutableSetOf())
            ?: throw MirrorException("Gofile did not include a file")
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
        val url = "$apiRoot/contents/$id?page=$page&pageSize=1000&sortField=createTime&sortDirection=-1"
        val response = pages.get(url, contentHeaders(accountToken))
        val content = GofileContents.parseContentPage(response.body)
            ?: throw MirrorException("Gofile returned an unreadable catalog")
        if (content.status == "error-rateLimit" || response.code == 429) {
            throw MirrorException("Gofile is busy, so this mirror was skipped.")
        }
        if (isGuestAuthStatus(content.status)) throw GofileAuthException(content.status)
        if (content.status == "error-notFound") throw MirrorException("Gofile could not find this file")
        if (content.status == "error-passwordRequired" || content.status == "error-passwordWrong") {
            throw MirrorException("Gofile password is not available, so this mirror was skipped")
        }
        if (!content.status.equals("ok", ignoreCase = true)) {
            throw MirrorException("Gofile could not read this file, so this mirror was skipped.")
        }
        if (!content.canAccess) throw MirrorException("Gofile could not read this file, so this mirror was skipped.")
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
        repeat(3) { attempt ->
            val response = pages.postEmpty("$apiRoot/accounts", createHeaders())
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
            if (!retry) throw MirrorException("Gofile could not open a guest session, so this mirror was skipped.")
        }
        throw MirrorException("Gofile could not open a guest session ($lastStatus), so this mirror was skipped.")
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
        return GofileWebsiteToken.compute(
            userAgent = userAgent,
            language = GofileWebsiteToken.LANGUAGE,
            accountToken = accountToken,
            unixSeconds = clockSeconds(),
            salt = secretSource(),
        )
    }

    private fun isGuestAuthStatus(status: String): Boolean {
        return status == "error-notPremium" || status == "error-wrongToken" || status == "error-notAuthenticated"
    }

    companion object {
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
