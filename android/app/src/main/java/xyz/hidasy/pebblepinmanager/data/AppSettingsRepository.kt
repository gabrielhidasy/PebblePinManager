package xyz.hidasy.pebblepinmanager.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Application-level preferences persisted locally on-device.
 */
data class AppSettings(
    /**
     * When true, timeline pin delivery is simulated locally without requiring a Pebble watch or
     * Pebble companion app, and the app exposes a Timeline tab inspecting what would have been sent.
     */
    val runWithoutWatch: Boolean = false,
)

/**
 * Local persistence contract for [AppSettings].
 */
interface AppSettingsRepository {
    fun observeSettings(): Flow<AppSettings>

    suspend fun getSettings(): AppSettings

    suspend fun setRunWithoutWatch(enabled: Boolean)
}

/**
 * [AppSettingsRepository] backed by AndroidX Preferences DataStore on-device.
 */
class DataStoreAppSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) : AppSettingsRepository {

    override fun observeSettings(): Flow<AppSettings> {
        return dataStore.data
            .catch { cause ->
                if (cause is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw cause
                }
            }
            .map { prefs ->
                AppSettings(
                    runWithoutWatch = prefs[RUN_WITHOUT_WATCH_KEY] ?: false,
                )
            }
    }

    override suspend fun getSettings(): AppSettings {
        return observeSettings().first()
    }

    override suspend fun setRunWithoutWatch(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[RUN_WITHOUT_WATCH_KEY] = enabled
        }
    }

    companion object {
        private const val STORE_NAME: String = "app_settings"
        private val RUN_WITHOUT_WATCH_KEY = booleanPreferencesKey("run_without_watch")

        fun create(
            context: Context,
            scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        ): DataStoreAppSettingsRepository {
            val appContext = context.applicationContext
            val store = PreferenceDataStoreFactory.create(scope = scope) {
                appContext.preferencesDataStoreFile(STORE_NAME)
            }
            return DataStoreAppSettingsRepository(dataStore = store)
        }
    }
}
