package com.thorfeeds.catalog.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.feedSettings by preferencesDataStore(name = "thor_feeds")

@Serializable
data class SourceRecord(
    val id: String,
    val kind: String,
    val location: String,
    val label: String,
    val lastError: String? = null,
    val needsSolve: Boolean = false,
) {
    val isRemote: Boolean get() = kind == KIND_REMOTE

    companion object {
        const val KIND_REMOTE = "remote"
        const val KIND_LOCAL = "local"
    }
}

class SettingsStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    val sources: Flow<List<SourceRecord>> = context.feedSettings.data.map { prefs ->
        val raw = prefs[SOURCES].orEmpty()
        if (raw.isBlank()) emptyList()
        else try {
            json.decodeFromString<List<SourceRecord>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    val folderUri: Flow<String?> = context.feedSettings.data.map { prefs ->
        prefs[FOLDER]?.takeIf { it.isNotBlank() }
    }

    val useSecondScreen: Flow<Boolean> = context.feedSettings.data.map { prefs ->
        prefs[SECOND_SCREEN] ?: true
    }

    suspend fun setSources(sources: List<SourceRecord>) {
        context.feedSettings.edit { prefs ->
            prefs[SOURCES] = json.encodeToString(sources)
        }
    }

    suspend fun setFolderUri(uri: String?) {
        context.feedSettings.edit { prefs ->
            if (uri.isNullOrBlank()) prefs.remove(FOLDER) else prefs[FOLDER] = uri
        }
    }

    suspend fun setUseSecondScreen(enabled: Boolean) {
        context.feedSettings.edit { prefs -> prefs[SECOND_SCREEN] = enabled }
    }

    val clearedFeedHosts: Flow<Set<String>> = context.feedSettings.data.map { prefs ->
        prefs[CLEARED_HOSTS] ?: emptySet()
    }

    suspend fun rememberClearedHost(host: String) {
        context.feedSettings.edit { prefs ->
            prefs[CLEARED_HOSTS] = (prefs[CLEARED_HOSTS] ?: emptySet()) + host
        }
    }

    suspend fun forgetClearedHost(host: String) {
        context.feedSettings.edit { prefs ->
            val next = (prefs[CLEARED_HOSTS] ?: emptySet()) - host
            if (next.isEmpty()) prefs.remove(CLEARED_HOSTS) else prefs[CLEARED_HOSTS] = next
        }
    }

    companion object {
        private val SOURCES = stringPreferencesKey("sources_json")
        private val FOLDER = stringPreferencesKey("games_folder_uri")
        private val SECOND_SCREEN = booleanPreferencesKey("use_second_screen")
        private val CLEARED_HOSTS = stringSetPreferencesKey("cleared_feed_hosts")
    }
}
