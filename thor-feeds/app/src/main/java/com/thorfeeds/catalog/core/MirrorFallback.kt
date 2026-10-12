package com.thorfeeds.catalog.core

import java.net.URI

enum class HostKind {
    GOFILE_SHARE,
    GOFILE_DIRECT,
    ONE_FICHIER_SHARE,
    ONE_FICHIER_DIRECT,
    VIKING_FILE,
    DATANODES_SHARE,
    DATANODES_DIRECT,
    DIRECT,
}

object MirrorFallback {
    fun firstIndex(uris: List<String>): Int? = uris.indices.firstOrNull { uris[it].isNotBlank() }

    fun nextIndex(count: Int, failedIndex: Int): Int? {
        val next = failedIndex + 1
        return next.takeIf { next in 0 until count }
    }
}

object UriHosts {
    fun kind(uri: String): HostKind {
        val host = hostOf(uri) ?: return HostKind.DIRECT
        val path = pathOf(uri)
        return when {
            host == "gofile.io" && !path.contains("/download/") -> HostKind.GOFILE_SHARE
            host == "gofile.io" || host.endsWith(".gofile.io") -> HostKind.GOFILE_DIRECT
            host == "1fichier.com" -> HostKind.ONE_FICHIER_SHARE
            host.endsWith(".1fichier.com") -> HostKind.ONE_FICHIER_DIRECT
            host == "vikingfile.com" -> HostKind.VIKING_FILE
            host == "datanodes.to" && !looksLikeDirectPath(path) -> HostKind.DATANODES_SHARE
            host.endsWith(".datanodes.to") || host == "datanodes.to" -> HostKind.DATANODES_DIRECT
            else -> HostKind.DIRECT
        }
    }

    fun label(uri: String): String {
        return when (kind(uri)) {
            HostKind.GOFILE_SHARE, HostKind.GOFILE_DIRECT -> "Gofile"
            HostKind.ONE_FICHIER_SHARE, HostKind.ONE_FICHIER_DIRECT -> "1fichier"
            HostKind.VIKING_FILE -> "VikingFile"
            HostKind.DATANODES_SHARE, HostKind.DATANODES_DIRECT -> "DataNodes"
            HostKind.DIRECT -> hostOf(uri) ?: "link"
        }
    }

    fun hostOf(uri: String): String? {
        return try {
            URI(uri.trim()).host?.lowercase()?.removePrefix("www.")?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    fun pathOf(uri: String): String {
        return try {
            URI(uri.trim()).path.orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    private fun looksLikeDirectPath(path: String): Boolean {
        val lower = path.lowercase()
        if (lower.contains("/download") || lower.contains("/d/")) return true
        val last = lower.substringAfterLast('/')
        return last.contains('.') && !last.endsWith(".html") && !last.endsWith(".php")
    }
}

object FeedUrls {
    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) throw IllegalArgumentException("Enter a feed URL")
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        val uri = try {
            URI(withScheme)
        } catch (_: Exception) {
            throw IllegalArgumentException("That feed URL is not valid")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            throw IllegalArgumentException("Feed URL must start with http or https")
        }
        if (uri.host.isNullOrBlank()) throw IllegalArgumentException("Feed URL needs a host")
        return withScheme
    }
}

object DisplayChoice {
    const val STATE_ON = 2

    data class Snapshot(
        val id: Int,
        val isOn: Boolean,
        val supportsPresentation: Boolean,
    )

    fun chooseSecondary(currentId: Int, displays: List<Snapshot>): Snapshot? {
        val others = displays.filter { it.id != currentId && it.isOn }
        return others.firstOrNull { it.supportsPresentation } ?: others.firstOrNull()
    }
}
