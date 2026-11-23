package xyz.hidasy.pebblepinmanager.pebble

import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.hidasy.pebblepinmanager.data.SimulatedTimelinePin
import xyz.hidasy.pebblepinmanager.data.SimulatedTimelineRepository
import xyz.hidasy.pebblepinmanager.domain.TimelineEvent
import xyz.hidasy.pebblepinmanager.domain.TimelineTimestampFormatter
import java.util.UUID

@Serializable
private data class SimulatedPinPayloadDto(
    val watchappUuid: String,
    val id: String,
    val time: String,
    val duration: Int? = null,
    val layout: SimulatedPinLayoutDto,
)

@Serializable
private data class SimulatedPinLayoutDto(
    val type: String,
    val title: String? = null,
    val subtitle: String? = null,
    val body: String? = null,
    val tinyIcon: String? = null,
    val smallIcon: String? = null,
    val largeIcon: String? = null,
    val primaryColor: String? = null,
    val secondaryColor: String? = null,
    val backgroundColor: String? = null,
)

/**
 * Formats a [TimelineEvent] through the real [TimelinePinMapper.toPebbleKitPin] mapper into a
 * human-readable JSON representation of what PebbleKit Android 2 would transmit to the watch.
 */
object SimulatedTimelinePayloadFormatter {
    private val prettyJson: Json = Json {
        prettyPrint = true
        encodeDefaults = false
        explicitNulls = false
    }

    fun formatPinPayloadJson(
        event: TimelineEvent,
        watchappUuid: UUID = PebblePinConfig.DEFAULT_WATCHAPP_UUID,
    ): String {
        val pin = TimelinePinMapper.toPebbleKitPin(event)
        val durationMinutes = pin.duration?.inWholeMinutes?.toInt()?.takeIf { it > 0 }
        val dto = SimulatedPinPayloadDto(
            watchappUuid = watchappUuid.toString(),
            id = pin.id,
            time = TimelineTimestampFormatter.toUtcIso8601(pin.startTime.toEpochMilliseconds()),
            duration = durationMinutes,
            layout = SimulatedPinLayoutDto(
                type = pin.layout.type.code,
                title = pin.layout.title,
                subtitle = pin.layout.subtitle,
                body = pin.layout.body,
                tinyIcon = pin.layout.tinyIcon,
                smallIcon = pin.layout.smallIcon,
                largeIcon = pin.layout.largeIcon,
                primaryColor = pin.layout.primaryColor,
                secondaryColor = pin.layout.secondaryColor,
                backgroundColor = pin.layout.backgroundColor,
            ),
        )
        return prettyJson.encodeToString(SimulatedPinPayloadDto.serializer(), dto)
    }
}

/**
 * [TimelinePinSender] that simulates Pebble timeline delivery locally on-device without requiring
 * a connected Pebble watch or Pebble companion app.
 *
 * Every insert, update, and delete is mapped via [TimelinePinMapper] and recorded in
 * [SimulatedTimelineRepository] so developers can inspect what would have been sent in the
 * Simulated Timeline tab.
 */
class SimulatedTimelinePinSender(
    private val simulatedRepository: SimulatedTimelineRepository,
    private val watchappUuid: UUID = PebblePinConfig.DEFAULT_WATCHAPP_UUID,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : TimelinePinSender {

    override suspend fun sendPin(event: TimelineEvent): PinSendResult {
        val normalized = event.normalized()
        val now = clockMillis()
        val payloadJson = SimulatedTimelinePayloadFormatter.formatPinPayloadJson(
            event = normalized,
            watchappUuid = watchappUuid,
        )
        val simulatedPin = SimulatedTimelinePin(
            id = normalized.id,
            watchappUuid = watchappUuid.toString(),
            title = normalized.title,
            description = normalized.description,
            startTimeMillis = normalized.startTimeMillis,
            durationMinutes = normalized.durationMinutes,
            iconUri = normalized.iconUri,
            backgroundColor = normalized.backgroundColor,
            primaryColor = normalized.primaryColor,
            secondaryColor = normalized.secondaryColor,
            layoutType = normalized.layoutType.name,
            source = normalized.source.name,
            sourceKey = normalized.sourceKey,
            payloadJson = payloadJson,
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        simulatedRepository.recordUpsert(pin = simulatedPin, timestampMillis = now)
        return PinSendResult.Success(normalized.id)
    }

    override suspend fun deletePin(pinId: String): PinDeleteResult {
        val sanitizedId = pinId.trim()
        if (sanitizedId.isEmpty()) {
            return PinDeleteResult.UnexpectedError("Pin ID must not be blank.")
        }
        val existed = simulatedRepository.recordDelete(
            pinId = sanitizedId,
            watchappUuid = watchappUuid.toString(),
            timestampMillis = clockMillis(),
        )
        return if (existed) {
            PinDeleteResult.Success(sanitizedId)
        } else {
            PinDeleteResult.UnknownPin(sanitizedId)
        }
    }

    override suspend fun getCompanionStatus(): PebbleCompanionStatus {
        return PebbleCompanionStatus.SimulatedNoWatch
    }

    override fun observeConnectedWatches(): Flow<List<ConnectedPebbleWatch>> {
        return flowOf(emptyList())
    }
}

/**
 * [TimelinePinSender] router that switches seamlessly at runtime between the real PebbleKit 2
 * transport and [SimulatedTimelinePinSender] based on the "Run without a watch" preference.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SwitchableTimelinePinSender(
    private val realSender: TimelinePinSender,
    private val simulatedSender: TimelinePinSender,
    private val runWithoutWatchFlow: Flow<Boolean>,
    private val isRunWithoutWatch: suspend () -> Boolean,
) : TimelinePinSender {

    override suspend fun sendPin(event: TimelineEvent): PinSendResult {
        return activeSender().sendPin(event)
    }

    override suspend fun deletePin(pinId: String): PinDeleteResult {
        return activeSender().deletePin(pinId)
    }

    override suspend fun getCompanionStatus(): PebbleCompanionStatus {
        return activeSender().getCompanionStatus()
    }

    override fun observeConnectedWatches(): Flow<List<ConnectedPebbleWatch>> {
        return runWithoutWatchFlow
            .distinctUntilChanged()
            .flatMapLatest { noWatch ->
                if (noWatch) {
                    simulatedSender.observeConnectedWatches()
                } else {
                    realSender.observeConnectedWatches()
                }
            }
    }

    override fun close() {
        runCatching { realSender.close() }
        runCatching { simulatedSender.close() }
    }

    private suspend fun activeSender(): TimelinePinSender {
        return if (isRunWithoutWatch()) simulatedSender else realSender
    }
}

/**
 * [WatchAppMessenger] decorator that short-circuits AppMessage sends while running without a watch.
 */
class SwitchableWatchAppMessenger(
    private val realMessenger: WatchAppMessenger,
    private val isRunWithoutWatch: suspend () -> Boolean,
) : WatchAppMessenger {

    override suspend fun sendToWatch(
        data: PebbleDictionary,
        targetWatch: WatchIdentifier?,
    ): WatchMessageResult {
        if (isRunWithoutWatch()) {
            return WatchMessageResult.WatchNotConnected
        }
        return realMessenger.sendToWatch(data = data, targetWatch = targetWatch)
    }
}
