package com.thorfeeds.catalog.core

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest

object Names {
    fun sanitizeFolder(name: String): String {
        val cleaned = name
            .replace(Regex("""[\\/:*?"<>|]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trim('.')
            .take(80)
        return cleaned.ifBlank { "game" }
    }

    fun sanitizeFile(name: String): String {
        val cleaned = name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
        if (cleaned.isBlank() || cleaned == "." || cleaned == "..") return "download"
        return cleaned.take(160)
    }

    fun fileNameFromUrl(url: String): String {
        val path = try {
            URI(url.trim()).path
        } catch (_: Exception) {
            null
        }.orEmpty()
        val last = path.substringAfterLast('/').substringBefore('?')
        if (last.isBlank()) return "download"
        val decoded = try {
            URLDecoder.decode(last, Charsets.UTF_8.name())
        } catch (_: Exception) {
            last
        }
        return sanitizeFile(decoded)
    }

    fun isPartialName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".partial") || lower.endsWith(".meta") || ".partial." in lower
    }

    fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun sourceKey(mirrorUri: String, fileName: String): String = sha256("$mirrorUri\n$fileName")
}
