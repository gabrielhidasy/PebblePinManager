package xyz.hidasy.pebblepinmanager.notifications

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
import java.io.IOException
import java.util.UUID

/**
 * Local on-device snapshot of a raw Android notification captured while Dev Mode is enabled,
 * so the user can inspect exact fields and interactively explore regex rules.
 */
@Serializable
data class RawNotificationRecord(
    val id: String = generateRecordId(),
    val capturedAtMillis: Long,
    val sourcePackage: String,
    val appLabel: String? = null,
    val notificationKey: String,
    val title: String,
    val text: String? = null,
    val bigText: String? = null,
    val subText: String? = null,
    val eventTimeMillis: Long,
    val postTimeMillis: Long = eventTimeMillis,
    val tag: String? = null,
    val notificationId: Int = 0,
    val channelId: String? = null,
    val category: String? = null,
    val isOngoing: Boolean = false,
    val isGroupSummary: Boolean = false,
    val pipelineOutcome: String? = null,
) {
    fun toCandidate(): NotificationPinCandidate = NotificationPinCandidate(
        sourcePackage = sourcePackage,
        notificationKey = notificationKey,
        title = title,
        text = text,
        eventTimeMillis = eventTimeMillis,
        subText = subText,
        bigText = bigText,
        tag = tag,
        notificationId = notificationId,
        isOngoing = isOngoing,
        isGroupSummary = isGroupSummary,
        appLabel = appLabel,
    )

    fun textFields(): NotificationTextFields = NotificationTextFields.fromCandidate(toCandidate())

    fun displayAppName(installedAppLabel: String? = null): String =
        installedAppLabel?.trim()?.takeIf { it.isNotEmpty() }
            ?: appLabel?.trim()?.takeIf { it.isNotEmpty() }
            ?: sourcePackage

    /**
     * Fingerprint of a notification's identity and payload content, used to avoid duplicate rows
     * when re-snapshotting unchanged active notifications while still keeping distinct updates.
     */
    fun contentFingerprint(): String = listOf(
        sourcePackage,
        notificationKey,
        postTimeMillis.toString(),
        title,
        text.orEmpty(),
        bigText.orEmpty(),
        subText.orEmpty(),
        isOngoing.toString(),
        isGroupSummary.toString(),
    ).joinToString(separator = "\u001F")

    companion object {
        fun generateRecordId(): String = "raw-${UUID.randomUUID().toString().take(8)}"

        fun fromCandidate(
            candidate: NotificationPinCandidate,
            capturedAtMillis: Long,
            postTimeMillis: Long = candidate.eventTimeMillis,
            channelId: String? = null,
            category: String? = null,
            pipelineOutcome: String? = null,
            id: String = generateRecordId(),
        ): RawNotificationRecord = RawNotificationRecord(
            id = id,
            capturedAtMillis = capturedAtMillis,
            sourcePackage = candidate.sourcePackage,
            appLabel = candidate.appLabel,
            notificationKey = candidate.notificationKey,
            title = candidate.title,
            text = candidate.text,
            bigText = candidate.bigText,
            subText = candidate.subText,
            eventTimeMillis = candidate.eventTimeMillis,
            postTimeMillis = postTimeMillis,
            tag = candidate.tag,
            notificationId = candidate.notificationId,
            channelId = channelId?.trim()?.takeIf { it.isNotEmpty() },
            category = category?.trim()?.takeIf { it.isNotEmpty() },
            isOngoing = candidate.isOngoing,
            isGroupSummary = candidate.isGroupSummary,
            pipelineOutcome = pipelineOutcome?.trim()?.takeIf { it.isNotEmpty() },
        )
    }
}

/**
 * Local persistence contract for raw notifications captured in Dev Mode.
 */
interface RawNotificationRepository {
    fun observeNotifications(): Flow<List<RawNotificationRecord>>

    suspend fun getNotifications(): List<RawNotificationRecord>

    suspend fun recordNotification(record: RawNotificationRecord)

    suspend fun recordAll(records: List<RawNotificationRecord>)

    suspend fun deleteNotification(id: String)

    suspend fun clearAll()
}

/**
 * [RawNotificationRepository] persisted strictly on-device with AndroidX Preferences DataStore.
 */
class DataStoreRawNotificationRepository(
    private val dataStore: DataStore<Preferences>,
    private val maxStoredRecords: Int = DEFAULT_MAX_RECORDS,
    private val json: Json = defaultJson,
) : RawNotificationRepository {

    override fun observeNotifications(): Flow<List<RawNotificationRecord>> {
        return dataStore.data
            .catch { cause ->
                if (cause is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw cause
                }
            }
            .map { prefs -> decodeRecords(prefs[RAW_NOTIFICATIONS_JSON_KEY]) }
    }

    override suspend fun getNotifications(): List<RawNotificationRecord> {
        return observeNotifications().first()
    }

    override suspend fun recordNotification(record: RawNotificationRecord) {
        recordAll(listOf(record))
    }

    override suspend fun recordAll(records: List<RawNotificationRecord>) {
        if (records.isEmpty()) return
        dataStore.edit { prefs ->
            val existing = decodeRecords(prefs[RAW_NOTIFICATIONS_JSON_KEY])
            val merged = mergeRecords(existing = existing, incoming = records)
                .sortedByDescending { it.capturedAtMillis }
                .take(maxStoredRecords)
            prefs[RAW_NOTIFICATIONS_JSON_KEY] = json.encodeToString(merged)
        }
    }

    override suspend fun deleteNotification(id: String) {
        dataStore.edit { prefs ->
            val remaining = decodeRecords(prefs[RAW_NOTIFICATIONS_JSON_KEY])
                .filterNot { it.id == id }
            prefs[RAW_NOTIFICATIONS_JSON_KEY] = json.encodeToString(remaining)
        }
    }

    override suspend fun clearAll() {
        dataStore.edit { prefs ->
            prefs.remove(RAW_NOTIFICATIONS_JSON_KEY)
        }
    }

    private fun mergeRecords(
        existing: List<RawNotificationRecord>,
        incoming: List<RawNotificationRecord>,
    ): List<RawNotificationRecord> {
        val byFingerprint = LinkedHashMap<String, RawNotificationRecord>()
        // Incoming records take priority for refreshed capture timestamps / outcomes.
        incoming.sortedByDescending { it.capturedAtMillis }.forEach { record ->
            byFingerprint.putIfAbsent(record.contentFingerprint(), record)
        }
        existing.forEach { record ->
            byFingerprint.putIfAbsent(record.contentFingerprint(), record)
        }
        return byFingerprint.values.toList()
    }

    private fun decodeRecords(raw: String?): List<RawNotificationRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<RawNotificationRecord>>(raw)
                .sortedByDescending { it.capturedAtMillis }
        }.getOrDefault(emptyList())
    }

    companion object {
        const val DEFAULT_MAX_RECORDS: Int = 100
        internal const val STORE_NAME: String = "raw_notifications_dev_log"
        private val RAW_NOTIFICATIONS_JSON_KEY = stringPreferencesKey("raw_notifications_json")

        private val defaultJson: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun create(
            context: Context,
            scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        ): DataStoreRawNotificationRepository {
            val appContext = context.applicationContext
            val store = PreferenceDataStoreFactory.create(scope = scope) {
                appContext.preferencesDataStoreFile(STORE_NAME)
            }
            return DataStoreRawNotificationRepository(dataStore = store)
        }
    }
}
