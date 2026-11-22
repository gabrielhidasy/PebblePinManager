package xyz.hidasy.pebblepinmanager.pebble

import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.ReceiveResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.launch
import xyz.hidasy.pebblepinmanager.PebblePinManagerApp
import java.util.UUID

/**
 * PebbleKit Android 2 listener service receiving AppMessages and open/close events from the
 * companion PebblePinManager watchapp.
 *
 * Acknowledges watch AppMessages promptly and processes sync / reschedule / delete / open-phone
 * actions asynchronously via [WatchPinSyncManager] so the watch outbox is never blocked while
 * responses are transmitted back.
 */
class PebbleWatchListenerService : BasePebbleListenerService() {

    private val syncManager: WatchPinSyncManager
        get() = (application as PebblePinManagerApp).container.watchPinSyncManager

    override fun onAppOpened(watchappUUID: UUID, watch: WatchIdentifier) {
        if (watchappUUID != PebblePinConfig.DEFAULT_WATCHAPP_UUID) {
            return
        }
        coroutineScope.launch {
            syncManager.syncRecentPins(targetWatch = watch)
        }
    }

    override suspend fun onMessageReceived(
        watchappUUID: UUID,
        data: PebbleDictionary,
        watch: WatchIdentifier,
    ): ReceiveResult {
        if (watchappUUID != PebblePinConfig.DEFAULT_WATCHAPP_UUID) {
            return ReceiveResult.Ack
        }
        coroutineScope.launch {
            syncManager.handleWatchMessage(data = data, watch = watch)
        }
        return ReceiveResult.Ack
    }
}
