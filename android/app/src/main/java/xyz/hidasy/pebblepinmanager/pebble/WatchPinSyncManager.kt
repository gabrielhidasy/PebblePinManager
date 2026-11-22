package xyz.hidasy.pebblepinmanager.pebble

import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.hidasy.pebblepinmanager.data.SentPinRecord
import xyz.hidasy.pebblepinmanager.data.SentPinRepository
import xyz.hidasy.pebblepinmanager.domain.TimelineEventValidator
import xyz.hidasy.pebblepinmanager.domain.ValidationResult
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/**
 * Coordinates bidirectional AppMessage sync and pin actions (reschedule, delete, open phone UI)
 * between the Pebble companion watchapp and Android local persistence / PebbleKit 2 timeline APIs.
 */
class WatchPinSyncManager(
    private val pinSender: TimelinePinSender,
    private val repository: SentPinRepository,
    private val messenger: WatchAppMessenger,
    private val onOpenPhoneUi: () -> PhoneUiOpenResult,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zoneIdProvider: () -> ZoneId = { clock.zone },
) {
    private val syncMutex = Mutex()
    private val generationCounter = AtomicInteger(0)

    /**
     * Pushes up to [WatchAppProtocol.MAX_PINS] most recent pins from [repository] to the watch.
     */
    suspend fun syncRecentPins(targetWatch: WatchIdentifier? = null): WatchSyncResult {
        return syncMutex.withLock {
            val nowMillis = clock.millis()
            val zoneId = zoneIdProvider()
            val recentPins = repository.observeSentPins().first().take(WatchAppProtocol.MAX_PINS)
            val generation = nextGeneration()

            val startResult = messenger.sendToWatch(
                data = WatchAppProtocol.encodeSyncStart(
                    count = recentPins.size,
                    generation = generation,
                ),
                targetWatch = targetWatch,
            )
            if (startResult !is WatchMessageResult.Delivered) {
                return@withLock WatchSyncResult.Skipped(startResult)
            }

            var sentCount = 0
            for ((index, record) in recentPins.withIndex()) {
                val summary = WatchPinSummaryFormatter.toWatchSummary(
                    record = record,
                    nowMillis = nowMillis,
                    zoneId = zoneId,
                )
                val itemResult = messenger.sendToWatch(
                    data = WatchAppProtocol.encodePinItem(
                        index = index,
                        totalCount = recentPins.size,
                        generation = generation,
                        summary = summary,
                    ),
                    targetWatch = targetWatch,
                )
                if (itemResult !is WatchMessageResult.Delivered) {
                    return@withLock WatchSyncResult.Partial(
                        sentCount = sentCount,
                        totalCount = recentPins.size,
                        lastResult = itemResult,
                    )
                }
                sentCount++
            }

            WatchSyncResult.Synced(count = sentCount)
        }
    }

    /**
     * Handles an incoming AppMessage from the Pebble watchapp.
     */
    suspend fun handleWatchMessage(
        data: PebbleDictionary,
        watch: WatchIdentifier,
    ): WatchHandleOutcome {
        return when (val request = WatchAppProtocol.decodeWatchRequest(data)) {
            null -> WatchHandleOutcome.Ignored

            WatchRequest.SyncRequest -> {
                val result = syncRecentPins(targetWatch = watch)
                WatchHandleOutcome.SyncHandled(result)
            }

            WatchRequest.OpenPhoneUi -> {
                val openResult = runCatching { onOpenPhoneUi() }
                    .getOrElse { error ->
                        PhoneUiOpenResult.Blocked(error.message ?: "Failed to open phone UI")
                    }
                messenger.sendToWatch(
                    data = WatchAppProtocol.encodeActionResult(
                        ok = openResult.watchStatusOk,
                        statusText = openResult.watchStatusText,
                    ),
                    targetWatch = watch,
                )
                WatchHandleOutcome.OpenPhoneHandled(openResult)
            }

            is WatchRequest.ReschedulePin -> handleReschedule(request, watch)

            is WatchRequest.DeletePin -> handleDelete(request, watch)
        }
    }

    private suspend fun handleReschedule(
        request: WatchRequest.ReschedulePin,
        watch: WatchIdentifier,
    ): WatchHandleOutcome {
        val existing = repository.getPinById(request.pinId)
        if (existing == null) {
            messenger.sendToWatch(
                data = WatchAppProtocol.encodeActionResult(ok = false, statusText = "Pin not found"),
                targetWatch = watch,
            )
            syncRecentPins(targetWatch = watch)
            return WatchHandleOutcome.ActionFailed("Pin '${request.pinId}' not found")
        }

        val zoneId = zoneIdProvider()
        val shiftedMillis = Instant.ofEpochMilli(existing.startTimeMillis)
            .atZone(zoneId)
            .plusDays(request.deltaDays.toLong())
            .plusMinutes(request.deltaMinutes.toLong())
            .toInstant()
            .toEpochMilli()

        val candidateEvent = existing.toTimelineEvent().copy(startTimeMillis = shiftedMillis)
        val nowMillis = clock.millis()
        val validatedEvent = when (val validation = TimelineEventValidator.validate(candidateEvent, nowMillis)) {
            is ValidationResult.Valid -> validation.event
            is ValidationResult.Invalid -> {
                messenger.sendToWatch(
                    data = WatchAppProtocol.encodeActionResult(ok = false, statusText = "Time out of range"),
                    targetWatch = watch,
                )
                return WatchHandleOutcome.ActionFailed(
                    validation.errors.firstOrNull()?.message ?: "Rescheduled time out of range",
                )
            }
        }

        return when (val sendResult = pinSender.sendPin(validatedEvent)) {
            is PinSendResult.Success -> {
                val updatedRecord = SentPinRecord.fromTimelineEvent(
                    event = validatedEvent,
                    sentAtMillis = existing.sentAtMillis,
                )
                repository.saveSentPin(updatedRecord)
                val newTimeLabel = WatchPinSummaryFormatter.formatCompactTime(
                    startTimeMillis = validatedEvent.startTimeMillis,
                    durationMinutes = null,
                    nowMillis = nowMillis,
                    zoneId = zoneId,
                )
                messenger.sendToWatch(
                    data = WatchAppProtocol.encodeActionResult(
                        ok = true,
                        statusText = "Moved: $newTimeLabel",
                    ),
                    targetWatch = watch,
                )
                syncRecentPins(targetWatch = watch)
                WatchHandleOutcome.PinRescheduled(
                    pinId = existing.id,
                    newStartTimeMillis = validatedEvent.startTimeMillis,
                )
            }

            PinSendResult.NoPebbleApp -> {
                sendActionError(watch, "No Pebble app")
                WatchHandleOutcome.ActionFailed("No Pebble app")
            }

            is PinSendResult.NoPermissions -> {
                sendActionError(watch, "No permission")
                WatchHandleOutcome.ActionFailed("No permission")
            }

            PinSendResult.UnsupportedAction -> {
                sendActionError(watch, "Update unsupported")
                WatchHandleOutcome.ActionFailed("Update unsupported")
            }

            is PinSendResult.UnexpectedError -> {
                sendActionError(watch, "Reschedule failed")
                WatchHandleOutcome.ActionFailed(sendResult.message)
            }
        }
    }

    private suspend fun handleDelete(
        request: WatchRequest.DeletePin,
        watch: WatchIdentifier,
    ): WatchHandleOutcome {
        return when (val deleteResult = pinSender.deletePin(request.pinId)) {
            is PinDeleteResult.Success,
            is PinDeleteResult.UnknownPin,
            -> {
                repository.deletePinRecord(request.pinId)
                messenger.sendToWatch(
                    data = WatchAppProtocol.encodeActionResult(ok = true, statusText = "Pin deleted"),
                    targetWatch = watch,
                )
                syncRecentPins(targetWatch = watch)
                WatchHandleOutcome.PinDeleted(request.pinId)
            }

            PinDeleteResult.NoPebbleApp -> {
                sendActionError(watch, "No Pebble app")
                WatchHandleOutcome.ActionFailed("No Pebble app")
            }

            is PinDeleteResult.NoPermissions -> {
                sendActionError(watch, "No permission")
                WatchHandleOutcome.ActionFailed("No permission")
            }

            PinDeleteResult.UnsupportedAction -> {
                sendActionError(watch, "Delete unsupported")
                WatchHandleOutcome.ActionFailed("Delete unsupported")
            }

            is PinDeleteResult.UnexpectedError -> {
                sendActionError(watch, "Delete failed")
                WatchHandleOutcome.ActionFailed(deleteResult.message)
            }
        }
    }

    private suspend fun sendActionError(watch: WatchIdentifier, text: String) {
        messenger.sendToWatch(
            data = WatchAppProtocol.encodeActionResult(ok = false, statusText = text),
            targetWatch = watch,
        )
    }

    private fun nextGeneration(): Int {
        val next = generationCounter.incrementAndGet() and 0xFFFF
        return if (next == 0) generationCounter.incrementAndGet() and 0xFFFF else next
    }
}

sealed interface WatchSyncResult {
    data class Synced(val count: Int) : WatchSyncResult
    data class Partial(
        val sentCount: Int,
        val totalCount: Int,
        val lastResult: WatchMessageResult,
    ) : WatchSyncResult
    data class Skipped(val reason: WatchMessageResult) : WatchSyncResult
}

sealed interface WatchHandleOutcome {
    data object Ignored : WatchHandleOutcome
    data class SyncHandled(val syncResult: WatchSyncResult) : WatchHandleOutcome
    data class OpenPhoneHandled(val result: PhoneUiOpenResult) : WatchHandleOutcome
    data class PinRescheduled(val pinId: String, val newStartTimeMillis: Long) : WatchHandleOutcome
    data class PinDeleted(val pinId: String) : WatchHandleOutcome
    data class ActionFailed(val reason: String) : WatchHandleOutcome
}
