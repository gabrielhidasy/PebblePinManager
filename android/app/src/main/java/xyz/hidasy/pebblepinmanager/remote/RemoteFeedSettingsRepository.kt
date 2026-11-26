package xyz.hidasy.pebblepinmanager.remote

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * Persistence contract for non-secret remote JSON pin feed configurations and sync metadata.
 */
interface RemoteFeedSettingsRepository {
    fun observeSettings(): Flow<RemotePinFeedsSettings>

    suspend fun getSettings(): RemotePinFeedsSettings

    suspend fun updateSettings(
        transform: (RemotePinFeedsSettings) -> RemotePinFeedsSettings,
    ): RemotePinFeedsSettings

    suspend fun upsertFeed(feed: RemotePinFeedConfig): RemotePinFeedConfig

    suspend fun getFeed(feedId: String): RemotePinFeedConfig?

    suspend fun updateFeed(
        feedId: String,
        transform: (RemotePinFeedConfig) -> RemotePinFeedConfig,
    ): RemotePinFeedConfig?

    suspend fun deleteFeed(feedId: String)
}

class DataStoreRemoteFeedSettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val json: Json = defaultJson,
) : RemoteFeedSettingsRepository {

    override fun observeSettings(): Flow<RemotePinFeedsSettings> {
        return dataStore.data
            .catch { cause ->
                if (cause is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw cause
                }
            }
            .map { prefs -> decodeSettings(prefs[FEEDS_JSON_KEY]) }
    }

    override suspend fun getSettings(): RemotePinFeedsSettings = observeSettings().first()

    override suspend fun updateSettings(
        transform: (RemotePinFeedsSettings) -> RemotePinFeedsSettings,
    ): RemotePinFeedsSettings {
        var result = RemotePinFeedsSettings()
        dataStore.edit { prefs ->
            val current = decodeSettings(prefs[FEEDS_JSON_KEY])
            val updated = transform(current).let { settings ->
                settings.copy(feeds = settings.feeds.map { it.normalized() })
            }
            result = updated
            prefs[FEEDS_JSON_KEY] = json.encodeToString(updated)
        }
        return result
    }

    override suspend fun upsertFeed(feed: RemotePinFeedConfig): RemotePinFeedConfig {
        val normalized = feed.normalized()
        updateSettings { current ->
            val existingIndex = current.feeds.indexOfFirst { it.id == normalized.id }
            val updatedFeeds = if (existingIndex >= 0) {
                current.feeds.toMutableList().apply { set(existingIndex, normalized) }
            } else {
                current.feeds + normalized
            }
            current.copy(feeds = updatedFeeds)
        }
        return normalized
    }

    override suspend fun getFeed(feedId: String): RemotePinFeedConfig? {
        return getSettings().feeds.firstOrNull { it.id == feedId }
    }

    override suspend fun updateFeed(
        feedId: String,
        transform: (RemotePinFeedConfig) -> RemotePinFeedConfig,
    ): RemotePinFeedConfig? {
        var updatedFeed: RemotePinFeedConfig? = null
        updateSettings { current ->
            val index = current.feeds.indexOfFirst { it.id == feedId }
            if (index < 0) return@updateSettings current
            val transformed = transform(current.feeds[index]).normalized()
            updatedFeed = transformed
            val updatedFeeds = current.feeds.toMutableList().apply { set(index, transformed) }
            current.copy(feeds = updatedFeeds)
        }
        return updatedFeed
    }

    override suspend fun deleteFeed(feedId: String) {
        updateSettings { current ->
            current.copy(feeds = current.feeds.filterNot { it.id == feedId })
        }
    }

    private fun decodeSettings(raw: String?): RemotePinFeedsSettings {
        if (raw.isNullOrBlank()) return RemotePinFeedsSettings()
        return runCatching {
            json.decodeFromString<RemotePinFeedsSettings>(raw)
        }.getOrDefault(RemotePinFeedsSettings())
    }

    companion object {
        const val STORE_NAME: String = "remote_pin_feeds"
        private val FEEDS_JSON_KEY = stringPreferencesKey("remote_pin_feeds_json")

        private val defaultJson: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun create(
            context: Context,
            scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        ): DataStoreRemoteFeedSettingsRepository {
            val appContext = context.applicationContext
            val store = PreferenceDataStoreFactory.create(scope = scope) {
                appContext.preferencesDataStoreFile(STORE_NAME)
            }
            return DataStoreRemoteFeedSettingsRepository(dataStore = store)
        }
    }
}
