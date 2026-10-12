package com.thorfeeds.catalog.net

import com.thorfeeds.catalog.core.Names
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

data class GofileListing(
    val status: String,
    val files: List<RemoteFile>,
    val folderIds: List<String>,
)

object GofileContents {
    private val json = Json { ignoreUnknownKeys = true }
    private val downloadLink = Regex("""https?://[a-z0-9.-]*gofile\.io/download/[^\s"'<>\\]+""", RegexOption.IGNORE_CASE)

    fun parse(body: String, mirrorUri: String, headers: Map<String, String>): GofileListing? {
        val root = try {
            json.parseToJsonElement(body) as? JsonObject
        } catch (_: Exception) {
            return null
        } ?: return null
        val status = root.string("status").ifBlank { "error" }
        if (!status.equals("ok", ignoreCase = true)) {
            return GofileListing(status = status, files = emptyList(), folderIds = emptyList())
        }
        val data = root["data"] as? JsonObject
            ?: return GofileListing(status = status, files = emptyList(), folderIds = emptyList())
        val files = mutableListOf<RemoteFile>()
        val folders = mutableListOf<String>()
        collect(data, mirrorUri, headers, files, folders, includeSelf = true)
        return GofileListing(status = status, files = files, folderIds = folders.distinct())
    }

    fun linksFromHtml(html: String, mirrorUri: String, headers: Map<String, String>): List<RemoteFile> {
        val normalized = html.replace("\\/", "/")
        return downloadLink.findAll(normalized).map { it.value.trim().trimEnd('\\') }.distinct().map { url ->
            val name = Names.fileNameFromUrl(url)
            RemoteFile(
                url = url,
                fileName = name,
                sizeBytes = null,
                sourceKey = Names.sourceKey(mirrorUri, name),
                headers = headers,
            )
        }.toList()
    }

    private fun collect(
        node: JsonObject,
        mirrorUri: String,
        headers: Map<String, String>,
        files: MutableList<RemoteFile>,
        folders: MutableList<String>,
        includeSelf: Boolean,
    ) {
        val type = node.string("type")
        if (includeSelf && type.equals("file", ignoreCase = true)) {
            fileFrom(node, mirrorUri, headers)?.let { files += it }
        }
        if (type.equals("folder", ignoreCase = true)) {
            val id = node.string("code").ifBlank { node.string("id") }
            if (!includeSelf && id.isNotBlank()) folders += id
        }
        val children = node["children"]
        when (children) {
            is JsonObject -> children.values.forEach { child ->
                (child as? JsonObject)?.let { collect(it, mirrorUri, headers, files, folders, includeSelf = true) }
            }
            is JsonArray -> children.forEach { child ->
                (child as? JsonObject)?.let { collect(it, mirrorUri, headers, files, folders, includeSelf = true) }
            }
            else -> Unit
        }
    }

    private fun fileFrom(node: JsonObject, mirrorUri: String, headers: Map<String, String>): RemoteFile? {
        val link = node.string("link").ifBlank { node.string("directLink") }
        if (link.isBlank()) return null
        val name = node.string("name").ifBlank { Names.fileNameFromUrl(link) }
        val safe = Names.sanitizeFile(name)
        val size = (node["size"] as? JsonPrimitive)?.longOrNull
        return RemoteFile(
            url = link,
            fileName = safe,
            sizeBytes = size,
            sourceKey = Names.sourceKey(mirrorUri, safe),
            headers = headers,
        )
    }

    private fun JsonObject.string(key: String): String {
        return (this[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    }
}
