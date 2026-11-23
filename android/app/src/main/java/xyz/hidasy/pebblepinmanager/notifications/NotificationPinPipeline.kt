package xyz.hidasy.pebblepinmanager.notifications

import xyz.hidasy.pebblepinmanager.data.SentPinRecord
import xyz.hidasy.pebblepinmanager.data.SentPinRepository
import xyz.hidasy.pebblepinmanager.domain.EventSource
import xyz.hidasy.pebblepinmanager.domain.TimelineEvent
import xyz.hidasy.pebblepinmanager.domain.TimelineEventValidator
import xyz.hidasy.pebblepinmanager.domain.ValidationResult
import xyz.hidasy.pebblepinmanager.pebble.PinSendResult
import xyz.hidasy.pebblepinmanager.pebble.TimelinePinSender

/**
 * Extension point reserved for Issue #7 (Android Notification Access / NotificationListenerService).
 *
 * Keeps future notification-to-pin ingestion completely decoupled from Jetpack Compose UI by
 * routing incoming notification payloads through the shared [TimelineEventValidator],
 * [TimelinePinSender], and [SentPinRepository].
 */
data class NotificationPinCandidate(
    val sourcePackage: String,
    val notificationKey: String,
    val title: String,
    val text: String?,
    val eventTimeMillis: Long,
)

/**
 * Strategy interface for filtering and mapping an Android notification into a [TimelineEvent].
 * Concrete opt-in allowlist & regex/time extraction rules will be implemented in Issue #7.
 */
fun interface NotificationPinMapper {
    fun mapOrNull(candidate: NotificationPinCandidate, existingPinId: String?): TimelineEvent?
}

/**
 * Coordinates deduplication, validation, delivery, and local history persistence for notifications.
 */
class NotificationPinPipeline(
    private val mapper: NotificationPinMapper,
    private val pinSender: TimelinePinSender,
    private val repository: SentPinRepository,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun process(candidate: NotificationPinCandidate): NotificationPipelineResult {
        val existing = repository.getPinBySourceKey(candidate.notificationKey)
        val mapped = mapper.mapOrNull(candidate, existing?.id)
            ?: return NotificationPipelineResult.Ignored

        val event = mapped.copy(
            source = EventSource.NOTIFICATION,
            sourceKey = candidate.notificationKey,
        )

        val validated = when (val validation = TimelineEventValidator.validate(event, clockMillis())) {
            is ValidationResult.Valid -> validation.event
            is ValidationResult.Invalid -> {
                return NotificationPipelineResult.ValidationFailed(
                    validation.errors.map { it.message },
                )
            }
        }

        return when (val sendResult = pinSender.sendPin(validated)) {
            is PinSendResult.Success -> {
                repository.saveSentPin(
                    SentPinRecord.fromTimelineEvent(validated, sentAtMillis = clockMillis()),
                )
                NotificationPipelineResult.Delivered(pinId = sendResult.pinId)
            }
            else -> NotificationPipelineResult.DeliveryFailed(sendResult)
        }
    }
}

sealed interface NotificationPipelineResult {
    data object Ignored : NotificationPipelineResult
    data class ValidationFailed(val reasons: List<String>) : NotificationPipelineResult
    data class Delivered(val pinId: String) : NotificationPipelineResult
    data class DeliveryFailed(val result: PinSendResult) : NotificationPipelineResult
}
