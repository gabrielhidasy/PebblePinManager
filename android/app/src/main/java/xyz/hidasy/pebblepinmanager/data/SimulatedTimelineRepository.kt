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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.hidasy.pebblepinmanager.domain.EventSource
import xyz.hidasy.pebblepinmanager.domain.PinLayoutType
import xyz.hidasy.pebblepinmanager.domain.TimelineEvent
import java.io.IOException
import java.util.UUID

/**
 * Snapshot of a pin captured locally while "Run without a watch" mode is enabled, including the
 * exact PebbleKit 2 payload JSON that would have been delivered to the watch.
 */
@Serializable
data class SimulatedTimelinePin(
    val id: String,
    val watchappUuid: String,
    val title: String,
    val description: String? = null,
    val startTimeMillis: Long,
    val durationMinutes: Int? = null,
    val iconUri: String = TimelineEvent.DEFAULT_ICON_URI,
    val backgroundColor: String? = null,
    val primaryColor: String? = null,
    val secondaryColor: String? = null,
    val layoutType: String = PinLayoutType.GENERIC_PIN.name,
    val source: String = EventSource.MANUAL.name,
    val sourceKey: String? = null,
    val payloadJson: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
) {
    val eventSource: EventSource
        get() = runCatching { EventSource.valueOf(source) }.getOrDefault(EventSource.MANUAL)

    val isAutoPin: Boolean
        get() = eventSource == EventSource.NOTIFICATION

    val pinLayoutType: PinLayoutType
        get() = runCatching { PinLayoutType.valueOf(layoutType) }.getOrDefault(PinLayoutType.GENERIC_PIN)

    fun toSentPinRecord(): SentPinRecord = SentPinRecord(
        id = id,
        title = title,
        description = description,
        startTimeMillis = startTimeMillis,
        durationMinutes = durationMinutes,
        iconUri = iconUri,
        backgroundColor = backgroundColor,
        layoutType = layoutType,
        source = source,
        sourceKey = sourceKey,
        sentAtMillis = updatedAtMillis,
    )
}

@Serializable
enum class SimulatedTimelineActionType {
    INSERT,
    UPDATE,
    DELETE,
}

/**
 * Immutable audit log entry for an intercepted PebbleKit 2 timeline call in watchless mode.
 */
@Serializable
data class SimulatedTimelineAction(
    val id: String = UUID.randomUUID().toString(),
    val type: SimulatedTimelineActionType,
    val pinId: String,
    val watchappUuid: String,
    val title: String? = null,
    val source: String? = null,
    val sourceKey: String? = null,
    val startTimeMillis: Long? = null,
    val payloadJson: String? = null,
    val timestampMillis: Long,
) {
    val eventSource: EventSource?
        get() = source?.let { raw -> runCatching { EventSource.valueOf(raw) }.getOrNull() }

    val isAutoPin: Boolean
        get() = eventSource == EventSource.NOTIFICATION
}

/**
 * Combined state of the simulated watch timeline: currently active pins plus chronological
 * delivery log of intercepted inserts, updates, and deletes.
 */
@Serializable
data class SimulatedTimelineSnapshot(
    val activePins: List<SimulatedTimelinePin> = emptyList(),
    val actionLog: List<SimulatedTimelineAction> = emptyList(),
)

/**
 * Local persistence contract for the simulated Pebble timeline used in watchless mode.
 */
interface SimulatedTimelineRepository {
    fun observeSnapshot(): Flow<SimulatedTimelineSnapshot>

    suspend fun getSnapshot(): SimulatedTimelineSnapshot

    /**
     * Inserts or updates [pin] on the simulated watch timeline and appends a corresponding
     * [SimulatedTimelineActionType.INSERT] or [SimulatedTimelineActionType.UPDATE] entry.
     */
    suspend fun recordUpsert(
        pin: SimulatedTimelinePin,
        timestampMillis: Long = System.currentTimeMillis(),
    ): SimulatedTimelineActionType

    /**
     * Removes [pinId] from the simulated watch timeline if present and appends a
     * [SimulatedTimelineActionType.DELETE] entry. Returns `true` if the pin existed on the
     * simulated timeline before deletion.
     */
    suspend fun recordDelete(
        pinId: String,
        watchappUuid: String,
        timestampMillis: Long = System.currentTimeMillis(),
    ): Boolean

    /** Clears all active simulated timeline pins and returns their pin IDs. */
    suspend fun clearActivePins(): List<String>

    /** Clears the intercepted action log while keeping active simulated pins intact. */
    suspend fun clearActionLog()

    /** Clears both active simulated pins and the action log, returning cleared active pin IDs. */
    suspend fun clearAll(): List<String>
}

/**
 * [SimulatedTimelineRepository] backed by AndroidX Preferences DataStore and JSON serialization.
 */
class DataStoreSimulatedTimelineRepository(
    private val dataStore: DataStore<Preferences>,
    private val maxStoredPins: Int = DEFAULT_MAX_STORED_PINS,
    private val maxLogEntries: Int = DEFAULT_MAX_LOG_ENTRIES,
    private val json: Json = defaultJson,
) : SimulatedTimelineRepository {

    override fun observeSnapshot(): Flow<SimulatedTimelineSnapshot> {
        return dataStore.data
            .catch { cause ->
                if (cause is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw cause
                }
            }
            .map { prefs -> decodeSnapshot(prefs[SNAPSHOT_JSON_KEY]) }
    }

    override suspend fun getSnapshot(): SimulatedTimelineSnapshot {
        return observeSnapshot().first()
    }

    override suspend fun recordUpsert(
        pin: SimulatedTimelinePin,
        timestampMillis: Long,
    ): SimulatedTimelineActionType {
        var resolvedType = SimulatedTimelineActionType.INSERT
        dataStore.edit { prefs ->
            val current = decodeSnapshot(prefs[SNAPSHOT_JSON_KEY])
            val existing = current.activePins.firstOrNull { it.id == pin.id }
            resolvedType = if (existing != null) {
                SimulatedTimelineActionType.UPDATE
            } else {
                SimulatedTimelineActionType.INSERT
            }
            val storedPin = pin.copy(
                createdAtMillis = existing?.createdAtMillis ?: pin.createdAtMillis,
                updatedAtMillis = timestampMillis,
            )
            val updatedPins = buildList {
                add(storedPin)
                addAll(current.activePins.filterNot { it.id == pin.id })
            }
                .sortedByDescending { it.updatedAtMillis }
                .take(maxStoredPins)
                .sortedWith(compareBy<SimulatedTimelinePin> { it.startTimeMillis }.thenBy { it.updatedAtMillis })

            val logEntry = SimulatedTimelineAction(
                type = resolvedType,
                pinId = storedPin.id,
                watchappUuid = storedPin.watchappUuid,
                title = storedPin.title,
                source = storedPin.source,
                sourceKey = storedPin.sourceKey,
                startTimeMillis = storedPin.startTimeMillis,
                payloadJson = storedPin.payloadJson,
                timestampMillis = timestampMillis,
            )
            val updatedLog = buildList {
                add(logEntry)
                addAll(current.actionLog)
            }.take(maxLogEntries)

            prefs[SNAPSHOT_JSON_KEY] = json.encodeToString(
                SimulatedTimelineSnapshot(
                    activePins = updatedPins,
                    actionLog = updatedLog,
                ),
            )
        }
        return resolvedType
    }

    override suspend fun recordDelete(
        pinId: String,
        watchappUuid: String,
        timestampMillis: Long,
    ): Boolean {
        val sanitizedId = pinId.trim()
        var existed = false
        dataStore.edit { prefs ->
            val current = decodeSnapshot(prefs[SNAPSHOT_JSON_KEY])
            val existing = current.activePins.firstOrNull { it.id == sanitizedId }
            existed = existing != null
            val remainingPins = current.activePins.filterNot { it.id == sanitizedId }

            val logEntry = SimulatedTimelineAction(
                type = SimulatedTimelineActionType.DELETE,
                pinId = sanitizedId,
                watchappUuid = watchappUuid,
                title = existing?.title,
                source = existing?.source,
                sourceKey = existing?.sourceKey,
                startTimeMillis = existing?.startTimeMillis,
                payloadJson = existing?.payloadJson,
                timestampMillis = timestampMillis,
            )
            val updatedLog = buildList {
                add(logEntry)
                addAll(current.actionLog)
            }.take(maxLogEntries)

            prefs[SNAPSHOT_JSON_KEY] = json.encodeToString(
                SimulatedTimelineSnapshot(
                    activePins = remainingPins,
                    actionLog = updatedLog,
                ),
            )
        }
        return existed
    }

    override suspend fun clearActivePins(): List<String> {
        var clearedIds: List<String> = emptyList()
        dataStore.edit { prefs ->
            val current = decodeSnapshot(prefs[SNAPSHOT_JSON_KEY])
            clearedIds = current.activePins.map { it.id }
            prefs[SNAPSHOT_JSON_KEY] = json.encodeToString(
                current.copy(activePins = emptyList()),
            )
        }
        return clearedIds
    }

    override suspend fun clearActionLog() {
        dataStore.edit { prefs ->
            val current = decodeSnapshot(prefs[SNAPSHOT_JSON_KEY])
            prefs[SNAPSHOT_JSON_KEY] = json.encodeToString(
                current.copy(actionLog = emptyList()),
            )
        }
    }

    override suspend fun clearAll(): List<String> {
        var clearedIds: List<String> = emptyList()
        dataStore.edit { prefs ->
            val current = decodeSnapshot(prefs[SNAPSHOT_JSON_KEY])
            clearedIds = current.activePins.map { it.id }
            prefs.remove(SNAPSHOT_JSON_KEY)
        }
        return clearedIds
    }

    private fun decodeSnapshot(raw: String?): SimulatedTimelineSnapshot {
        if (raw.isNullOrBlank()) return SimulatedTimelineSnapshot()
        return runCatching {
            val decoded = json.decodeFromString<SimulatedTimelineSnapshot>(raw)
            decoded.copy(
                activePins = decoded.activePins.sortedWith(
                    compareBy<SimulatedTimelinePin> { it.startTimeMillis }.thenBy { it.updatedAtMillis },
                ),
                actionLog = decoded.actionLog.sortedByDescending { it.timestampMillis },
            )
        }.getOrDefault(SimulatedTimelineSnapshot())
    }

    companion object {
        const val DEFAULT_MAX_STORED_PINS: Int = 100
        const val DEFAULT_MAX_LOG_ENTRIES: Int = 200
        private const val STORE_NAME: String = "simulated_timeline_store"
        private val SNAPSHOT_JSON_KEY = stringPreferencesKey("simulated_timeline_snapshot_json")

        private val defaultJson: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun create(
            context: Context,
            scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        ): DataStoreSimulatedTimelineRepository {
            val appContext = context.applicationContext
            val store = PreferenceDataStoreFactory.create(scope = scope) {
                appContext.preferencesDataStoreFile(STORE_NAME)
            }
            return DataStoreSimulatedTimelineRepository(dataStore = store)
        }
    }
}
