package xyz.hidasy.pebblepinmanager.emulatorbridge

import io.rebble.pebblekit2.PebbleKitProviderContract
import io.rebble.pebblekit2.common.model.WatchIdentifier
import io.rebble.pebblekit2.server.BasePebbleKitProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PebbleKit Android 2 ContentProvider exposing the currently active emulated Pebble watch
 * (`basalt` or `emery`) to PebblePinManager via [io.rebble.pebblekit2.client.DefaultPebbleInfoRetriever].
 */
class EmulatorBridgeProvider : BasePebbleKitProvider() {
    private val hasInitialized = AtomicBoolean(false)

    override fun onCreate(): Boolean {
        val created = super.onCreate()
        instance = this
        return created
    }

    fun initializeOnce() {
        if (hasInitialized.compareAndSet(false, true)) {
            initialize()
        }
    }

    override fun getConnectedWatches(): Flow<List<Map<String, Any?>>> {
        return BridgeConfigStore.configFlow.map { cfg ->
            if (!cfg.connected) {
                emptyList()
            } else {
                listOf(
                    mapOf(
                        PebbleKitProviderContract.ConnectedWatch.ID to cfg.watchId,
                        PebbleKitProviderContract.ConnectedWatch.NAME to cfg.watchName,
                        PebbleKitProviderContract.ConnectedWatch.PLATFORM to cfg.platform,
                        PebbleKitProviderContract.ConnectedWatch.REVISION to cfg.revision,
                        PebbleKitProviderContract.ConnectedWatch.FIRMWARE_VERSION_MAJOR to cfg.firmwareMajor,
                        PebbleKitProviderContract.ConnectedWatch.FIRMWARE_VERSION_MINOR to cfg.firmwareMinor,
                        PebbleKitProviderContract.ConnectedWatch.FIRMWARE_VERSION_PATCH to cfg.firmwarePatch,
                        PebbleKitProviderContract.ConnectedWatch.FIRMWARE_VERSION_TAG to cfg.firmwareTag,
                    ),
                )
            }
        }
    }

    override fun getActiveApp(watch: WatchIdentifier): Flow<Map<String, Any?>?> {
        return flowOf(null)
    }

    companion object {
        @Volatile
        private var instance: EmulatorBridgeProvider? = null

        fun initializeIfCreated() {
            instance?.initializeOnce()
        }
    }
}
