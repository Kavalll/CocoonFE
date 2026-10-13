package com.thorfeeds.catalog.core

enum class CatalogSort {
    NEWEST,
    NAME,
    SIZE,
    POPULARITY,
    ;

    companion object {
        fun fromStored(raw: String?): CatalogSort {
            return entries.firstOrNull { it.name == raw } ?: NEWEST
        }
    }
}

object CatalogOrder {
    fun sort(rows: List<CatalogRow>, order: CatalogSort, popularity: Map<String, Long>): List<CatalogRow> {
        return when (order) {
            CatalogSort.NEWEST -> rows.sortedWith(
                compareByDescending<CatalogRow> { it.latest.uploadEpochMs }
                    .thenBy { it.canonicalTitle.lowercase() },
            )
            CatalogSort.NAME -> rows.sortedWith(
                compareBy<CatalogRow> { it.canonicalTitle.lowercase() }
                    .thenByDescending { it.latest.uploadEpochMs },
            )
            CatalogSort.SIZE -> rows.sortedWith(
                compareBy<CatalogRow> { it.latest.fileSizeBytes == null }
                    .thenByDescending { it.latest.fileSizeBytes ?: 0L }
                    .thenBy { it.canonicalTitle.lowercase() },
            )
            CatalogSort.POPULARITY -> rows.sortedWith(
                compareBy<CatalogRow> { popularity[TitleCanonicalizer.identityKey(it.canonicalTitle)] == null }
                    .thenByDescending { popularity[TitleCanonicalizer.identityKey(it.canonicalTitle)] ?: 0L }
                    .thenBy { it.canonicalTitle.lowercase() },
            )
        }
    }
}
