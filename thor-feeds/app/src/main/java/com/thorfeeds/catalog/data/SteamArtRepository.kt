package com.thorfeeds.catalog.data

import com.thorfeeds.catalog.core.CatalogArt
import com.thorfeeds.catalog.net.HttpIdentity
import com.thorfeeds.catalog.core.SteamArt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class SteamArtRepository(
    directory: File,
    http: OkHttpClient,
) {
    private val file = File(directory, "steam-art-index.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private val memory = mutableMapOf<String, ArtEntry>()
    private var loaded = false
    private val steam = http.newBuilder()
        .dispatcher(Dispatcher().apply {
            maxRequests = 3
            maxRequestsPerHost = 3
        })
        .callTimeout(15, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun cached(): Map<String, CatalogArt> = lock.withLock {
        load()
        memory.mapValues { (_, entry) -> entry.toArt() }
    }

    suspend fun resolve(requests: List<Pair<String, String>>, onEach: (String, CatalogArt) -> Unit) {
        val pending = lock.withLock {
            load()
            requests.filter { (key, _) -> key !in memory }
        }
        if (pending.isEmpty()) return
        val gate = Semaphore(3)
        coroutineScope {
            pending.map { (key, title) ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val found = lookup(title) ?: return@withPermit
                        lock.withLock { memory[key] = found }
                        onEach(key, found.toArt())
                    }
                }
            }.awaitAll()
        }
        lock.withLock { persist() }
    }

    private fun lookup(title: String): ArtEntry? {
        val search = get(
            "https://store.steampowered.com/api/storesearch/?term=${encode(title)}&l=english&cc=US",
        ) ?: return null
        val appId = SteamArt.choose(title, SteamArt.parseSearch(search))
            ?: return ArtEntry(appId = null, popularity = null)
        val details = get(
            "https://store.steampowered.com/api/appdetails?appids=$appId&filters=recommendations",
        )
        val popularity = details?.let { SteamArt.parseRecommendations(it, appId) }
        return ArtEntry(appId = appId, popularity = popularity)
    }

    private fun get(url: String): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", HttpIdentity.FEED_BROWSER_USER_AGENT)
                .header("Accept", "application/json")
                .build()
            steam.newCall(request).execute().use { response ->
                if (response.code !in 200..299) null else response.body?.string()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun load() {
        if (loaded) return
        loaded = true
        val text = try {
            file.takeIf { it.isFile }?.readText()
        } catch (_: Exception) {
            null
        } ?: return
        val stored = try {
            json.decodeFromString<ArtCache>(text)
        } catch (_: Exception) {
            return
        }
        memory.putAll(stored.entries)
    }

    private fun persist() {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(ArtCache(memory.toMap())))
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    @Serializable
    private data class ArtCache(val entries: Map<String, ArtEntry> = emptyMap())

    @Serializable
    private data class ArtEntry(val appId: Int? = null, val popularity: Long? = null) {
        fun toArt(): CatalogArt = CatalogArt(appId, popularity)
    }
}
