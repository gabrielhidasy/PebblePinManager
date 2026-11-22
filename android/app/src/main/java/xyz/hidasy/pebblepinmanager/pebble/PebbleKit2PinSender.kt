package xyz.hidasy.pebblepinmanager.pebble

import android.content.Context
import io.rebble.pebblekit2.client.DefaultPebbleAndroidAppPicker
import io.rebble.pebblekit2.client.DefaultPebbleInfoRetriever
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.client.PebbleAndroidAppPicker
import io.rebble.pebblekit2.client.PebbleInfoRetriever
import io.rebble.pebblekit2.client.PebbleSender
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import xyz.hidasy.pebblepinmanager.domain.TimelineEvent
import java.util.UUID

/**
 * [TimelinePinSender] backed by PebbleKit Android 2 (`io.rebble.pebblekit2:client`).
 *
 * Uses [DefaultPebbleSender.insertTimelinePin] and [DefaultPebbleSender.deleteTimelinePin] for
 * direct local timeline delivery through the Core Devices Pebble app (`libpebble3`) or microPebble
 * to both Pebble Time (`basalt`) and Pebble Time 2 (`emery`) watches.
 */
class PebbleKit2PinSender(
    private val pebbleSender: PebbleSender,
    private val infoRetriever: PebbleInfoRetriever,
    private val appPicker: PebbleAndroidAppPicker,
    private val watchappUuid: UUID = PebblePinConfig.DEFAULT_WATCHAPP_UUID,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TimelinePinSender {

    override suspend fun sendPin(event: TimelineEvent): PinSendResult = withContext(ioDispatcher) {
        val normalized = event.normalized()
        try {
            val pin = TimelinePinMapper.toPebbleKitPin(normalized)
            val result = pebbleSender.insertTimelinePin(watchappUuid, pin)
            TimelinePinMapper.toPinSendResult(
                pinId = normalized.id,
                watchappUuid = watchappUuid,
                result = result,
            )
        } catch (e: SecurityException) {
            PinSendResult.NoPermissions(watchappUuid.toString())
        } catch (e: Exception) {
            PinSendResult.UnexpectedError(
                message = e.message ?: "Failed to communicate with the Pebble companion app.",
                cause = e,
            )
        }
    }

    override suspend fun deletePin(pinId: String): PinDeleteResult = withContext(ioDispatcher) {
        val sanitizedId = pinId.trim()
        if (sanitizedId.isEmpty()) {
            return@withContext PinDeleteResult.UnexpectedError("Pin ID must not be blank.")
        }
        try {
            val result = pebbleSender.deleteTimelinePin(watchappUuid, sanitizedId)
            TimelinePinMapper.toPinDeleteResult(
                pinId = sanitizedId,
                watchappUuid = watchappUuid,
                result = result,
            )
        } catch (e: SecurityException) {
            PinDeleteResult.NoPermissions(watchappUuid.toString())
        } catch (e: Exception) {
            PinDeleteResult.UnexpectedError(
                message = e.message ?: "Failed to delete pin '$sanitizedId' via Pebble companion app.",
                cause = e,
            )
        }
    }

    override suspend fun getCompanionStatus(): PebbleCompanionStatus = withContext(ioDispatcher) {
        try {
            val eligible = appPicker.getAllEligibleApps()
            val selected = appPicker.getCurrentlySelectedApp()
            if (selected != null) {
                PebbleCompanionStatus.Available(
                    selectedPackage = selected,
                    eligiblePackages = eligible,
                )
            } else if (eligible.isEmpty()) {
                PebbleCompanionStatus.Unavailable(
                    reason = "No PebbleKit 2 companion app detected. Install the Core Devices Pebble app (v1.0.7.7+) or microPebble.",
                )
            } else {
                PebbleCompanionStatus.Unavailable(
                    reason = "A Pebble companion app is installed (${eligible.joinToString()}), but none is currently selected.",
                )
            }
        } catch (e: Exception) {
            PebbleCompanionStatus.Unavailable(
                reason = e.message ?: "Unable to query installed Pebble companion apps.",
            )
        }
    }

    override fun observeConnectedWatches(): Flow<List<ConnectedPebbleWatch>> {
        return infoRetriever.getConnectedWatches()
            .map { watches -> watches.map(TimelinePinMapper::toConnectedPebbleWatch) }
            .catch { emit(emptyList()) }
            .flowOn(ioDispatcher)
    }

    override fun close() {
        runCatching { pebbleSender.close() }
    }

    companion object {
        fun create(
            context: Context,
            watchappUuid: UUID = PebblePinConfig.DEFAULT_WATCHAPP_UUID,
            pebbleSender: PebbleSender? = null,
        ): PebbleKit2PinSender {
            val appContext = context.applicationContext
            return PebbleKit2PinSender(
                pebbleSender = pebbleSender ?: DefaultPebbleSender(appContext),
                infoRetriever = DefaultPebbleInfoRetriever(appContext),
                appPicker = DefaultPebbleAndroidAppPicker.getInstance(appContext),
                watchappUuid = watchappUuid,
            )
        }
    }
}
