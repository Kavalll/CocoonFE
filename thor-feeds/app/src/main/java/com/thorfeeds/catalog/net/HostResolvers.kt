package com.thorfeeds.catalog.net

import com.thorfeeds.catalog.core.HostKind
import com.thorfeeds.catalog.core.Names
import com.thorfeeds.catalog.core.UriHosts
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class DirectResolver {
    fun resolve(uri: String): ResolvedMirror {
        val name = Names.fileNameFromUrl(uri)
        return ResolvedMirror(
            label = UriHosts.label(uri),
            files = listOf(
                RemoteFile(
                    url = uri,
                    fileName = name,
                    sizeBytes = null,
                    sourceKey = Names.sourceKey(uri, name),
                    headers = mapOf("User-Agent" to HttpIdentity.USER_AGENT),
                ),
            ),
        )
    }
}

class OneFichierResolver(private val pages: PageFetcher) {
    fun resolve(uri: String): ResolvedMirror {
        val first = pages.get(uri, browserHeaders(uri))
        if (first.isFile) return directFile(first.finalUrl.ifBlank { uri }, uri)
        HtmlShare.subdomainFile(first.body, "1fichier.com")?.let { return directFile(it, uri) }
        val form = HtmlShare.downloadForm(first.body, first.finalUrl.ifBlank { uri })
            ?: throw MirrorException("1fichier did not offer a file")
        val second = pages.postForm(form.action, form.fields, browserHeaders(uri))
        if (second.isFile) return directFile(second.finalUrl.ifBlank { uri }, uri)
        HtmlShare.subdomainFile(second.body, "1fichier.com")?.let { return directFile(it, uri) }
        if ("wait" in second.body.lowercase()) {
            throw MirrorException("1fichier asked us to wait, so this mirror was skipped")
        }
        throw MirrorException("1fichier did not return a direct file")
    }
}

class VikingFileResolver(private val pages: PageFetcher) {
    fun resolve(uri: String): ResolvedMirror {
        val page = pages.get(uri, browserHeaders(uri))
        if (page.isFile) return directFile(page.finalUrl.ifBlank { uri }, uri)
        jsonLink(page.body)?.let { return directFile(it, uri) }
        val embedded = HtmlShare.urls(page.body).firstOrNull { candidate ->
            val host = UriHosts.hostOf(candidate) ?: return@firstOrNull false
            host != "vikingfile.com" && HtmlShare.looksLikePayload(candidate)
        }
        if (embedded != null) return directFile(embedded, uri)
        val lower = page.body.lowercase()
        if ("turnstile" in lower || "captcha-download" in lower) {
            throw MirrorException("VikingFile asked for an interactive check, so this mirror was skipped")
        }
        throw MirrorException("VikingFile did not return a direct file")
    }

    private fun jsonLink(body: String): String? {
        val match = Regex(""""link"\s*:\s*"(https?://[^"\\]+)"""").find(body) ?: return null
        return match.groupValues[1].replace("\\/", "/")
    }
}

class DataNodesResolver(private val pages: PageFetcher) {
    fun resolve(uri: String): ResolvedMirror {
        val first = pages.get(uri, browserHeaders(uri))
        if (first.isFile) return directFile(first.finalUrl.ifBlank { uri }, uri)
        directCandidate(first.body, uri)?.let { return directFile(it, uri) }
        val form = HtmlShare.downloadForm(first.body, first.finalUrl.ifBlank { uri })
        if (form != null) {
            val second = pages.postForm(form.action, form.fields, browserHeaders(uri))
            if (second.isFile) return directFile(second.finalUrl.ifBlank { uri }, uri)
            directCandidate(second.body, uri)?.let { return directFile(it, uri) }
        }
        throw MirrorException("DataNodes did not return a direct file")
    }

    private fun directCandidate(html: String, pageUri: String): String? {
        return HtmlShare.urls(html).firstOrNull { candidate ->
            candidate != pageUri && UriHosts.kind(candidate) == HostKind.DATANODES_DIRECT
        }
    }
}

class GofileResolver(
    private val pages: PageFetcher,
    apiBase: String = "https://api.gofile.io",
    siteBase: String = "https://gofile.io",
    private val userAgent: String = HttpIdentity.USER_AGENT,
    private val salts: List<String> = listOf(GofileWebsiteToken.PRIMARY_SALT, GofileWebsiteToken.ALTERNATE_SALT),
    private val clockSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
) {
    private val apiRoot = apiBase.trimEnd('/')
    private val siteRoot = siteBase.trimEnd('/')

    fun resolve(uri: String): ResolvedMirror {
        val id = contentId(uri) ?: throw MirrorException("Unrecognized Gofile link")
        val account = createGuest()
        val headers = downloadHeaders(account)
        var lastStatus = "no response"
        for (salt in salts.distinct()) {
            val listing = fetchListing(id, account, salt, headers, uri)
            if (listing.status == "error-passwordRequired") {
                throw MirrorException("Gofile password is not available, so this mirror was skipped")
            }
            if (listing.status == "error-notFound") {
                throw MirrorException("Gofile could not find this file")
            }
            if (listing.files.isNotEmpty()) {
                return ResolvedMirror(label = "Gofile", files = listing.files)
            }
            if (listing.status == "ok" && listing.folderIds.isNotEmpty()) {
                val nested = listing.folderIds.take(5).flatMap { folderId ->
                    fetchListing(folderId, account, salts.first(), headers, uri).files
                }
                if (nested.isNotEmpty()) return ResolvedMirror(label = "Gofile", files = nested)
            }
            lastStatus = listing.status
            if (listing.status == "ok") break
        }
        val page = pages.get("$siteRoot/d/$id", headers)
        val fromPage = GofileContents.linksFromHtml(page.body, uri, headers)
        if (fromPage.isNotEmpty()) return ResolvedMirror(label = "Gofile", files = fromPage)
        throw MirrorException("Gofile ($lastStatus)")
    }

    private fun fetchListing(
        id: String,
        account: String,
        salt: String,
        headers: Map<String, String>,
        mirrorUri: String,
    ): GofileListing {
        val token = GofileWebsiteToken.compute(userAgent, "en-US", account, clockSeconds(), salt)
        val url = apiRoot.toHttpUrl().newBuilder()
            .addPathSegment("contents")
            .addPathSegment(id)
            .addQueryParameter("contentFilter", "")
            .addQueryParameter("page", "1")
            .addQueryParameter("pageSize", "1000")
            .addQueryParameter("sortField", "name")
            .addQueryParameter("sortDirection", "1")
            .build()
        val body = pages.get(
            url.toString(),
            headers + mapOf(
                "Authorization" to "Bearer $account",
                "X-Website-Token" to token,
                "X-BL" to "en-US",
                "Accept" to "application/json",
            ),
        )
        if (body.code >= 500) return GofileListing("http-${body.code}", emptyList(), emptyList())
        return GofileContents.parse(body.body, mirrorUri, headers)
            ?: GofileListing(status = "unreadable", files = emptyList(), folderIds = emptyList())
    }

    private fun createGuest(): String {
        val response = pages.postJson(
            "$apiRoot/accounts",
            "{}",
            mapOf("User-Agent" to userAgent, "Accept" to "application/json"),
        )
        if (response.code >= 400) throw MirrorException("Gofile did not open a guest session")
        val token = Regex(""""token"\s*:\s*"([^"]+)"""").find(response.body)?.groupValues?.get(1)
        if (token.isNullOrBlank()) throw MirrorException("Gofile did not open a guest session")
        return token
    }

    private fun downloadHeaders(accountToken: String): Map<String, String> {
        return mapOf(
            "User-Agent" to userAgent,
            "Referer" to "https://gofile.io/",
            "Origin" to "https://gofile.io",
            "Cookie" to "accountToken=$accountToken",
        )
    }

    companion object {
        fun contentId(uri: String): String? {
            if (UriHosts.kind(uri) != HostKind.GOFILE_SHARE) return null
            val path = UriHosts.pathOf(uri).trimEnd('/')
            if (!path.startsWith("/d/")) return null
            val id = path.removePrefix("/d/")
            if (id.isBlank() || "/" in id) return null
            return id
        }
    }
}

class RealMirrorRouter(
    private val gofile: GofileResolver,
    private val oneFichier: OneFichierResolver,
    private val vikingFile: VikingFileResolver,
    private val dataNodes: DataNodesResolver,
    private val direct: DirectResolver = DirectResolver(),
) : MirrorRouter {
    override suspend fun resolve(uri: String): ResolvedMirror {
        if (uri.isBlank()) throw MirrorException("Empty download link")
        return when (UriHosts.kind(uri)) {
            HostKind.GOFILE_SHARE -> gofile.resolve(uri)
            HostKind.ONE_FICHIER_SHARE -> oneFichier.resolve(uri)
            HostKind.VIKING_FILE -> vikingFile.resolve(uri)
            HostKind.DATANODES_SHARE -> dataNodes.resolve(uri)
            else -> direct.resolve(uri)
        }
    }

    companion object {
        fun create(client: OkHttpClient): RealMirrorRouter {
            val pages = PageFetcher(client)
            return RealMirrorRouter(
                gofile = GofileResolver(pages),
                oneFichier = OneFichierResolver(pages),
                vikingFile = VikingFileResolver(pages),
                dataNodes = DataNodesResolver(pages),
            )
        }
    }
}

internal fun directFile(url: String, mirrorUri: String): ResolvedMirror {
    val name = Names.fileNameFromUrl(url)
    return ResolvedMirror(
        label = UriHosts.label(mirrorUri),
        files = listOf(
            RemoteFile(
                url = url,
                fileName = name,
                sizeBytes = null,
                sourceKey = Names.sourceKey(mirrorUri, name),
                headers = mapOf(
                    "User-Agent" to HttpIdentity.USER_AGENT,
                    "Referer" to mirrorUri,
                ),
            ),
        ),
    )
}

internal fun browserHeaders(pageUrl: String): Map<String, String> {
    return mapOf(
        "User-Agent" to HttpIdentity.USER_AGENT,
        "Accept" to "text/html,application/json;q=0.9,*/*;q=0.8",
        "Referer" to pageUrl,
    )
}
