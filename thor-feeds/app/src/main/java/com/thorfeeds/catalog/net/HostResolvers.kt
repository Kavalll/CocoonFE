package com.thorfeeds.catalog.net

import com.thorfeeds.catalog.core.HostKind
import com.thorfeeds.catalog.core.Names
import com.thorfeeds.catalog.core.UriHosts
import android.content.Context
import okhttp3.OkHttpClient
import java.io.File

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
        fun create(context: Context, client: OkHttpClient): RealMirrorRouter {
            val pages = PageFetcher(client)
            val secret = GofileScriptSecret(pages)
            return RealMirrorRouter(
                gofile = GofileResolver(
                    pages = pages,
                    tokens = FileGofileTokenStore(File(context.filesDir, "gofile-guest-token.json")),
                    secretSource = { secret.secret() },
                ),
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
