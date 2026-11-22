package xyz.hidasy.pebblepinmanager.pebble

import io.rebble.pebblekit2.client.PebbleSender
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.TransmissionResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Transport abstraction for sending AppMessage dictionaries to the companion Pebble watchapp.
 */
interface WatchAppMessenger {
    suspend fun sendToWatch(
        data: PebbleDictionary,
        targetWatch: WatchIdentifier? = null,
    ): WatchMessageResult
}

sealed interface WatchMessageResult {
    data object Delivered : WatchMessageResult
    data object AppNotOpen : WatchMessageResult
    data object WatchNotConnected : WatchMessageResult
    data object NoPermissions : WatchMessageResult
    data class Failed(val reason: String) : WatchMessageResult
}

/**
 * [WatchAppMessenger] implementation backed by PebbleKit Android 2 (`PebbleSender.sendDataToPebble`).
 */
class PebbleKit2WatchAppMessenger(
    private val pebbleSender: PebbleSender,
    private val watchappUuid: UUID = PebblePinConfig.DEFAULT_WATCHAPP_UUID,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : WatchAppMessenger {

    override suspend fun sendToWatch(
        data: PebbleDictionary,
        targetWatch: WatchIdentifier?,
    ): WatchMessageResult = withContext(ioDispatcher) {
        try {
            val watches = targetWatch?.let(::listOf)
            val results = pebbleSender.sendDataToPebble(watchappUuid, data, watches)
                ?: return@withContext WatchMessageResult.WatchNotConnected

            if (results.isEmpty()) {
                return@withContext WatchMessageResult.WatchNotConnected
            }

            val outcomes = results.values
            when {
                outcomes.any { it is TransmissionResult.Success } -> WatchMessageResult.Delivered
                outcomes.any { it is TransmissionResult.FailedNoPermissions } -> WatchMessageResult.NoPermissions
                outcomes.any { it is TransmissionResult.FailedDifferentAppOpen } -> WatchMessageResult.AppNotOpen
                outcomes.any { it is TransmissionResult.FailedWatchNotConnected } -> WatchMessageResult.WatchNotConnected
                outcomes.any { it is TransmissionResult.FailedTimeout } -> WatchMessageResult.Failed("Watch timed out")
                outcomes.any { it is TransmissionResult.FailedWatchNacked } -> WatchMessageResult.Failed("Watch rejected message")
                else -> {
                    val unknown = outcomes.filterIsInstance<TransmissionResult.Unknown>().firstOrNull()
                    WatchMessageResult.Failed(unknown?.message ?: "Unknown watch transmission failure")
                }
            }
        } catch (e: SecurityException) {
            WatchMessageResult.NoPermissions
        } catch (e: Exception) {
            WatchMessageResult.Failed(e.message ?: "Failed to deliver AppMessage to watch")
        }
    }
}
