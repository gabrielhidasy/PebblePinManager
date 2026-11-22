package xyz.hidasy.pebblepinmanager.data

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
 * [SentPinRepository] persisted locally with AndroidX Preferences DataStore and JSON serialization.
 */
class DataStoreSentPinRepository(
    private val dataStore: DataStore<Preferences>,
    private val maxStoredPins: Int = DEFAULT_MAX_STORED_PINS,
    private val json: Json = defaultJson,
) : SentPinRepository {

    override fun observeSentPins(): Flow<List<SentPinRecord>> {
        return dataStore.data
            .catch { cause ->
                if (cause is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw cause
                }
            }
            .map { prefs -> decodeRecords(prefs[SENT_PINS_JSON_KEY]) }
    }

    override suspend fun saveSentPin(record: SentPinRecord) {
        dataStore.edit { prefs ->
            val existing = decodeRecords(prefs[SENT_PINS_JSON_KEY])
            val updated = buildList {
                add(record)
                addAll(existing.filterNot { it.id == record.id })
            }
                .sortedByDescending { it.sentAtMillis }
                .take(maxStoredPins)
            prefs[SENT_PINS_JSON_KEY] = json.encodeToString(updated)
        }
    }

    override suspend fun getPinById(id: String): SentPinRecord? {
        return observeSentPins().first().firstOrNull { it.id == id }
    }

    override suspend fun getPinBySourceKey(sourceKey: String): SentPinRecord? {
        return observeSentPins().first().firstOrNull { it.sourceKey == sourceKey }
    }

    override suspend fun deletePinRecord(id: String) {
        dataStore.edit { prefs ->
            val remaining = decodeRecords(prefs[SENT_PINS_JSON_KEY])
                .filterNot { it.id == id }
            prefs[SENT_PINS_JSON_KEY] = json.encodeToString(remaining)
        }
    }

    override suspend fun clearAll() {
        dataStore.edit { prefs ->
            prefs.remove(SENT_PINS_JSON_KEY)
        }
    }

    private fun decodeRecords(raw: String?): List<SentPinRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<SentPinRecord>>(raw)
                .sortedByDescending { it.sentAtMillis }
        }.getOrDefault(emptyList())
    }

    companion object {
        const val DEFAULT_MAX_STORED_PINS: Int = 100
        private const val STORE_NAME: String = "sent_timeline_pins"
        private val SENT_PINS_JSON_KEY = stringPreferencesKey("sent_pins_json")

        private val defaultJson: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun create(
            context: Context,
            scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        ): DataStoreSentPinRepository {
            val appContext = context.applicationContext
            val store = PreferenceDataStoreFactory.create(scope = scope) {
                appContext.preferencesDataStoreFile(STORE_NAME)
            }
            return DataStoreSentPinRepository(dataStore = store)
        }
    }
}
