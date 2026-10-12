package com.thorfeeds.catalog.core

data class ParsedRelease(
    val id: String = "",
    val rawTitle: String,
    val canonicalTitle: String,
    val uploadDate: String,
    val uploadEpochMs: Long,
    val fileSizeLabel: String,
    val fileSizeBytes: Long?,
    val uris: List<String>,
)

data class ParsedFeed(
    val name: String,
    val releases: List<ParsedRelease>,
    val skippedEntries: Int,
)

data class CatalogRow(
    val latest: ParsedRelease,
    val older: List<ParsedRelease>,
) {
    val canonicalTitle: String get() = latest.canonicalTitle
    val all: List<ParsedRelease> get() = listOf(latest) + older
}

class FeedFormatException(message: String) : Exception(message)
