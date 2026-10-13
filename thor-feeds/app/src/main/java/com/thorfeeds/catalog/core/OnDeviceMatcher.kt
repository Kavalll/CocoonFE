package com.thorfeeds.catalog.core

data class StoredEntry(
    val name: String,
    val isDirectory: Boolean,
    val hasPayload: Boolean,
)

object OnDeviceMatcher {
    /**
     * A game is on device when a top-level file, a non-empty top-level folder,
     * or a file inside a top-level folder matches the canonical title.
     * Partial downloads do not count. Callers should omit them.
     */
    fun isPresent(canonicalTitle: String, entries: List<StoredEntry>): Boolean {
        val wanted = TitleCanonicalizer.matchKey(canonicalTitle)
        if (wanted.length < 2) return false
        return entries.any { entry ->
            if (Names.isPartialName(entry.name)) return@any false
            if (entry.isDirectory && !entry.hasPayload) return@any false
            val base = if (entry.isDirectory) entry.name else stripExtension(entry.name)
            TitleCanonicalizer.matchKey(base) == wanted
        }
    }

    fun presentKeys(catalogTitles: List<String>, entries: List<StoredEntry>): Set<String> {
        val onDisk = entries.mapNotNull { entry ->
            if (Names.isPartialName(entry.name)) return@mapNotNull null
            if (entry.isDirectory && !entry.hasPayload) return@mapNotNull null
            val base = if (entry.isDirectory) entry.name else stripExtension(entry.name)
            TitleCanonicalizer.matchKey(base).takeIf { it.length >= 2 }
        }.toSet()
        if (onDisk.isEmpty()) return emptySet()
        return catalogTitles.mapNotNull { title ->
            val wanted = TitleCanonicalizer.matchKey(title)
            if (wanted.length < 2 || wanted !in onDisk) null else TitleCanonicalizer.identityKey(title)
        }.toSet()
    }

    private fun stripExtension(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        if (dot <= 0) return fileName
        return fileName.substring(0, dot)
    }
}
