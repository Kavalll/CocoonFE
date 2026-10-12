package com.thorfeeds.catalog.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

object FeedParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun looksLikeHtml(body: String): Boolean {
        val head = body.trimStart().take(800).lowercase()
        return head.startsWith("<!doctype") ||
            head.startsWith("<html") ||
            head.startsWith("<head") ||
            "just a moment" in head
    }

    fun parse(text: String): ParsedFeed {
        if (text.isBlank()) throw FeedFormatException("The feed is empty.")
        if (looksLikeHtml(text)) {
            throw FeedFormatException(
                "This is a web page, not a feed. Challenge pages are ignored.",
            )
        }
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            throw FeedFormatException("The feed is not JSON.")
        } ?: throw FeedFormatException("The feed must be a JSON object.")

        val name = root.string("name").ifBlank { "Feed" }
        val downloads = root["downloads"] as? JsonArray
            ?: throw FeedFormatException("The feed is missing a downloads list.")

        val releases = mutableListOf<ParsedRelease>()
        var skipped = 0
        for (element in downloads) {
            val entry = element as? JsonObject
            if (entry == null) {
                skipped += 1
                continue
            }
            val title = entry.string("title")
            if (title.isBlank()) {
                skipped += 1
                continue
            }
            val uploadDate = entry.string("uploadDate")
            val fileSize = entry.string("fileSize").ifBlank { "Unknown" }
            releases += ParsedRelease(
                rawTitle = title,
                canonicalTitle = TitleCanonicalizer.canonical(title),
                uploadDate = uploadDate,
                uploadEpochMs = UploadDates.parseEpochMs(uploadDate),
                fileSizeLabel = fileSize,
                fileSizeBytes = FileSizeParser.parseBytes(fileSize),
                uris = readUris(entry["uris"]),
            )
        }
        return ParsedFeed(name = name, releases = releases, skippedEntries = skipped)
    }

    private fun readUris(element: kotlinx.serialization.json.JsonElement?): List<String> {
        return when (element) {
            is JsonArray -> element.mapNotNull { item ->
                (item as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            }
            is JsonPrimitive -> listOfNotNull(element.contentOrNull?.trim()?.takeIf { it.isNotEmpty() })
            else -> emptyList()
        }
    }

    private fun JsonObject.string(key: String): String {
        val primitive = this[key] as? JsonPrimitive ?: return ""
        return primitive.contentOrNull?.trim().orEmpty()
    }
}
