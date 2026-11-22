package xyz.hidasy.pebblepinmanager.pebble

import android.util.Log
import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.ReceiveResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.launch
import xyz.hidasy.pebblepinmanager.AppContainer
import xyz.hidasy.pebblepinmanager.PebblePinManagerApp
import java.util.UUID

/**
 * PebbleKit Android 2 listener service receiving AppMessages and open/close events from the
 * companion PebblePinManager watchapp.
 *
 * Acknowledges watch AppMessages promptly and dispatches sync / reschedule / delete / open-phone
 * actions on [AppContainer.applicationScope] so an immediate service unbind after returning
 * [ReceiveResult.Ack] never cancels in-flight phone-to-watch responses or handoff notifications.
 */
class PebbleWatchListenerService : BasePebbleListenerService() {

    private val container: AppContainer
        get() = (application as PebblePinManagerApp).container

    override fun onAppOpened(watchappUUID: UUID, watch: WatchIdentifier) {
        if (watchappUUID != PebblePinConfig.DEFAULT_WATCHAPP_UUID) {
            return
        }
        Log.d(TAG, "Watchapp opened on watch=$watch; triggering recent pins sync")
        container.applicationScope.launch {
            val result = container.watchPinSyncManager.syncRecentPins(targetWatch = watch)
            Log.d(TAG, "Watchapp open sync finished: $result")
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
        val decoded = WatchAppProtocol.decodeWatchRequest(data)
        Log.i(TAG, "Received watch message ($decoded) from watch=$watch")
        container.applicationScope.launch {
            val outcome = container.watchPinSyncManager.handleWatchMessage(data = data, watch = watch)
            Log.i(TAG, "Handled watch message ($decoded) -> $outcome")
        }
        return ReceiveResult.Ack
    }

    companion object {
        private const val TAG: String = "PebbleWatchListener"
    }
}
