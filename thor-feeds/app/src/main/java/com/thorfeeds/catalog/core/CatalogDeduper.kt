package com.thorfeeds.catalog.core

object CatalogDeduper {
    /**
     * One catalog row per canonical title. The newest uploadDate wins.
     * Older releases stay attached so the detail view can show history.
     * A tie keeps the later item in the input.
     */
    fun rows(releases: List<ParsedRelease>): List<CatalogRow> {
        if (releases.isEmpty()) return emptyList()
        return releases.withIndex()
            .groupBy { TitleCanonicalizer.identityKey(it.value.canonicalTitle) }
            .map { (_, indexed) ->
                val sorted = indexed.sortedWith(
                    compareByDescending<IndexedValue<ParsedRelease>> { it.value.uploadEpochMs }
                        .thenByDescending { it.index },
                )
                val ordered = sorted.map { it.value }
                CatalogRow(latest = ordered.first(), older = ordered.drop(1))
            }
            .sortedWith(
                compareByDescending<CatalogRow> { it.latest.uploadEpochMs }
                    .thenBy { it.canonicalTitle.lowercase() },
            )
    }

    fun matchesQuery(row: CatalogRow, query: String): Boolean {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return true
        if (row.canonicalTitle.lowercase().contains(needle)) return true
        return row.all.any { it.rawTitle.lowercase().contains(needle) }
    }
}
