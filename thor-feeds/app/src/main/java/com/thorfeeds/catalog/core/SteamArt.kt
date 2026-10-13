package com.thorfeeds.catalog.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

data class SteamHit(
    val id: Int,
    val type: String,
    val name: String,
)

data class CatalogArt(
    val appId: Int?,
    val popularity: Long?,
) {
    val capsuleUrl: String?
        get() = appId?.let { SteamArt.capsuleUrl(it) }
}

/**
 * Matches a canonical title to one Steam app. A title is used only when exactly
 * one store app has the same name. Anything else is left without art.
 */
object SteamArt {
    private val punctuation = Regex("""[^a-z0-9]+""")
    private val json = Json { ignoreUnknownKeys = true }

    fun normalize(name: String): String {
        return name.lowercase().replace(punctuation, " ").trim().replace(Regex("""\s+"""), " ")
    }

    fun choose(query: String, hits: List<SteamHit>): Int? {
        val wanted = normalize(query)
        if (wanted.isBlank()) return null
        val exact = hits
            .filter { it.type.equals("app", ignoreCase = true) }
            .filter { normalize(it.name) == wanted }
            .map { it.id }
            .distinct()
        return exact.singleOrNull()
    }

    fun capsuleUrl(appId: Int): String {
        return "https://cdn.akamai.steamstatic.com/steam/apps/$appId/library_600x900.jpg"
    }

    fun parseSearch(body: String): List<SteamHit> {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return emptyList()
        val items = root["items"] as? JsonArray ?: return emptyList()
        return items.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = (obj["id"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            val type = (obj["type"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            SteamHit(id, type, name)
        }
    }

    fun parseRecommendations(body: String, appId: Int): Long? {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return null
        val node = root[appId.toString()] as? JsonObject ?: return null
        val data = node["data"] as? JsonObject ?: return null
        val recommendations = data["recommendations"] as? JsonObject ?: return null
        return (recommendations["total"] as? JsonPrimitive)?.longOrNull
    }
}
