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
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * Local persistence contract for [NotificationRuleSettings].
 */
interface NotificationSettingsRepository {
    fun observeSettings(): Flow<NotificationRuleSettings>

    suspend fun getSettings(): NotificationRuleSettings

    suspend fun updateSettings(transform: (NotificationRuleSettings) -> NotificationRuleSettings)
}

/**
 * [NotificationSettingsRepository] backed by AndroidX Preferences DataStore on-device.
 */
class DataStoreNotificationSettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val json: Json = defaultJson,
) : NotificationSettingsRepository {

    override fun observeSettings(): Flow<NotificationRuleSettings> {
        return dataStore.data
            .catch { cause ->
                if (cause is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw cause
                }
            }
            .map { prefs -> decodeSettings(prefs[SETTINGS_JSON_KEY]) }
    }

    override suspend fun getSettings(): NotificationRuleSettings {
        return observeSettings().first()
    }

    override suspend fun updateSettings(
        transform: (NotificationRuleSettings) -> NotificationRuleSettings,
    ) {
        dataStore.edit { prefs ->
            val current = decodeSettings(prefs[SETTINGS_JSON_KEY])
            val updated = transform(current).normalized()
            prefs[SETTINGS_JSON_KEY] = json.encodeToString(updated)
        }
    }

    private fun decodeSettings(raw: String?): NotificationRuleSettings {
        if (raw.isNullOrBlank()) return NotificationRuleSettings()
        return runCatching {
            json.decodeFromString<NotificationRuleSettings>(raw).normalized()
        }.getOrDefault(NotificationRuleSettings())
    }

    private fun NotificationRuleSettings.normalized(): NotificationRuleSettings = copy(
        allowedPackages = allowedPackages.map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
        keywordFilter = keywordFilter.trim(),
        excludeKeywordFilter = excludeKeywordFilter.trim(),
        titleRegexPattern = titleRegexPattern.trim(),
        descriptionRegexPattern = descriptionRegexPattern.trim(),
        timeRegexPattern = timeRegexPattern.trim(),
        globalRegexRules = globalRegexRules.map { it.normalized() },
        appRules = appRules.entries
            .mapNotNull { (rawKey, config) ->
                val pkg = rawKey.trim().ifEmpty { config.packageName.trim() }
                if (pkg.isEmpty()) {
                    null
                } else {
                    pkg to config.copy(packageName = pkg).normalized()
                }
            }
            .toMap(),
        defaultBackgroundColor = defaultBackgroundColor?.trim()?.takeIf { it.isNotEmpty() },
    )

    companion object {
        private const val STORE_NAME: String = "notification_rule_settings"
        private val SETTINGS_JSON_KEY = stringPreferencesKey("notification_rule_settings_json")

        private val defaultJson: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun create(
            context: Context,
            scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        ): DataStoreNotificationSettingsRepository {
            val appContext = context.applicationContext
            val store = PreferenceDataStoreFactory.create(scope = scope) {
                appContext.preferencesDataStoreFile(STORE_NAME)
            }
            return DataStoreNotificationSettingsRepository(dataStore = store)
        }
    }
}
