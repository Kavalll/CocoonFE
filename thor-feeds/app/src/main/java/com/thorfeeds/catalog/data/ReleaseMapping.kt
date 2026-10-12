package com.thorfeeds.catalog.data

import com.thorfeeds.catalog.core.Names
import com.thorfeeds.catalog.core.ParsedRelease
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

fun ParsedRelease.toEntity(feedId: String): ReleaseEntity {
    return ReleaseEntity(
        id = Names.sha256("$feedId\n$rawTitle\n$uploadDate"),
        feedId = feedId,
        rawTitle = rawTitle,
        canonicalTitle = canonicalTitle,
        uploadDate = uploadDate,
        uploadEpochMs = uploadEpochMs,
        fileSizeLabel = fileSizeLabel,
        fileSizeBytes = fileSizeBytes,
        urisJson = json.encodeToString(ListSerializer(String.serializer()), uris),
    )
}

fun ReleaseEntity.toParsed(): ParsedRelease {
    val uris = try {
        json.decodeFromString(ListSerializer(String.serializer()), urisJson)
    } catch (_: Exception) {
        emptyList()
    }
    return ParsedRelease(
        id = id,
        rawTitle = rawTitle,
        canonicalTitle = canonicalTitle,
        uploadDate = uploadDate,
        uploadEpochMs = uploadEpochMs,
        fileSizeLabel = fileSizeLabel,
        fileSizeBytes = fileSizeBytes,
        uris = uris,
    )
}
