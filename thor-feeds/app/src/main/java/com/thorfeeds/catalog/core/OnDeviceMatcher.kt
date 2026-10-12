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
        return catalogTitles.map { TitleCanonicalizer.identityKey(it) }
            .filter { key ->
                val title = catalogTitles.first { TitleCanonicalizer.identityKey(it) == key }
                isPresent(title, entries)
            }
            .toSet()
    }

    private fun stripExtension(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        if (dot <= 0) return fileName
        return fileName.substring(0, dot)
    }
}
