package xyz.hidasy.pebblepinmanager.emulatorbridge

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BridgeWatchConfig(
    val watchId: String = "emu-pebble-basalt",
    val watchName: String = "Pebble Time Emulator",
    val platform: String = "basalt",
    val revision: String = "ev2_4",
    val firmwareMajor: Int = 4,
    val firmwareMinor: Int = 4,
    val firmwarePatch: Int = 0,
    val firmwareTag: String = "emu",
    val bridgeUrl: String = DEFAULT_BRIDGE_URL,
    val connected: Boolean = true,
) {
    companion object {
        const val DEFAULT_BRIDGE_URL: String = "http://127.0.0.1:18442"

        fun forPlatform(
            platform: String,
            bridgeUrl: String = DEFAULT_BRIDGE_URL,
            connected: Boolean = true,
            customName: String? = null,
        ): BridgeWatchConfig {
            val normalized = platform.trim().lowercase()
            return when (normalized) {
                "emery" -> BridgeWatchConfig(
                    watchId = "emu-pebble-emery",
                    watchName = customName?.takeIf { it.isNotBlank() } ?: "Pebble Time 2 Emulator",
                    platform = "emery",
                    revision = "robert_evt",
                    bridgeUrl = bridgeUrl,
                    connected = connected,
                )
                else -> BridgeWatchConfig(
                    watchId = "emu-pebble-$normalized",
                    watchName = customName?.takeIf { it.isNotBlank() } ?: "Pebble Time Emulator",
                    platform = normalized.ifBlank { "basalt" },
                    revision = "ev2_4",
                    bridgeUrl = bridgeUrl,
                    connected = connected,
                )
            }
        }
    }
}

object BridgeConfigStore {
    private const val PREFS_NAME = "pebble_emulator_bridge_config"
    private const val KEY_PLATFORM = "platform"
    private const val KEY_WATCH_NAME = "watch_name"
    private const val KEY_BRIDGE_URL = "bridge_url"
    private const val KEY_CONNECTED = "connected"

    private val _configFlow = MutableStateFlow(BridgeWatchConfig())
    val configFlow: StateFlow<BridgeWatchConfig> = _configFlow.asStateFlow()

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val platform = prefs.getString(KEY_PLATFORM, "basalt") ?: "basalt"
            val watchName = prefs.getString(KEY_WATCH_NAME, null)
            val bridgeUrl = prefs.getString(KEY_BRIDGE_URL, BridgeWatchConfig.DEFAULT_BRIDGE_URL)
                ?: BridgeWatchConfig.DEFAULT_BRIDGE_URL
            val connected = prefs.getBoolean(KEY_CONNECTED, true)
            _configFlow.value = BridgeWatchConfig.forPlatform(
                platform = platform,
                bridgeUrl = bridgeUrl,
                connected = connected,
                customName = watchName,
            )
            initialized = true
        }
    }

    fun update(
        context: Context,
        platform: String? = null,
        watchName: String? = null,
        bridgeUrl: String? = null,
        connected: Boolean? = null,
    ): BridgeWatchConfig {
        init(context)
        val current = _configFlow.value
        val nextPlatform = platform?.trim()?.takeIf { it.isNotEmpty() } ?: current.platform
        val nextUrl = bridgeUrl?.trim()?.takeIf { it.isNotEmpty() } ?: current.bridgeUrl
        val nextConnected = connected ?: current.connected
        val nextName = watchName?.trim()?.takeIf { it.isNotEmpty() }
            ?: if (nextPlatform != current.platform) null else current.watchName

        val updated = BridgeWatchConfig.forPlatform(
            platform = nextPlatform,
            bridgeUrl = nextUrl,
            connected = nextConnected,
            customName = nextName,
        )

        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PLATFORM, updated.platform)
            .putString(KEY_WATCH_NAME, updated.watchName)
            .putString(KEY_BRIDGE_URL, updated.bridgeUrl)
            .putBoolean(KEY_CONNECTED, updated.connected)
            .apply()

        _configFlow.value = updated
        return updated
    }
}
