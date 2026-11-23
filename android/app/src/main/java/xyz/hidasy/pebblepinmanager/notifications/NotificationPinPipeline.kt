package xyz.hidasy.pebblepinmanager.notifications

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.hidasy.pebblepinmanager.data.SentPinRecord
import xyz.hidasy.pebblepinmanager.data.SentPinRepository
import xyz.hidasy.pebblepinmanager.domain.EventSource
import xyz.hidasy.pebblepinmanager.domain.TimelineEvent
import xyz.hidasy.pebblepinmanager.domain.TimelineEventValidator
import xyz.hidasy.pebblepinmanager.domain.ValidationResult
import xyz.hidasy.pebblepinmanager.pebble.PinDeleteResult
import xyz.hidasy.pebblepinmanager.pebble.PinSendResult
import xyz.hidasy.pebblepinmanager.pebble.TimelinePinSender

/**
 * Pure-Kotlin snapshot of an incoming Android notification destined for rule filtering and
 * Pebble Timeline pin conversion.
 */
data class NotificationPinCandidate(
    val sourcePackage: String,
    val notificationKey: String,
    val title: String,
    val text: String?,
    val eventTimeMillis: Long,
    val subText: String? = null,
    val bigText: String? = null,
    val tag: String? = null,
    val notificationId: Int = 0,
    val isOngoing: Boolean = false,
    val isGroupSummary: Boolean = false,
    val appLabel: String? = null,
) {
    fun deduplicationKey(): String = NotificationDeduplicationKey.compute(
        sourcePackage = sourcePackage,
        notificationKey = notificationKey,
        tag = tag,
        notificationId = notificationId,
    )
}

/**
 * Computes a stable deduplication key so Android notification updates (same key or tag+id)
 * overwrite the existing Pebble Timeline pin ID cleanly instead of creating duplicates.
 */
object NotificationDeduplicationKey {
    fun compute(
        sourcePackage: String,
        notificationKey: String,
        tag: String? = null,
        notificationId: Int = 0,
    ): String {
        val normalizedKey = notificationKey.trim()
        if (normalizedKey.isNotEmpty()) return normalizedKey
        val pkg = sourcePackage.trim()
        val normalizedTag = tag?.trim().orEmpty()
        return "$pkg|$normalizedTag|$notificationId"
    }
}

/**
 * Strategy interface for filtering and mapping an Android notification into a [TimelineEvent].
 */
fun interface NotificationPinMapper {
    suspend fun mapOrNull(candidate: NotificationPinCandidate, existingPinId: String?): TimelineEvent?
}

/**
 * Coordinates deduplication, validation, delivery, removal, and local history persistence for
 * notifications routed from [PebbleNotificationListenerService].
 *
 * Uses a [Mutex] to serialize concurrent updates so bursts of Android notification callbacks for
 * the same notification key never race and create duplicate pin IDs.
 */
class NotificationPinPipeline(
    private val mapper: NotificationPinMapper,
    private val pinSender: TimelinePinSender,
    private val repository: SentPinRepository,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    suspend fun process(candidate: NotificationPinCandidate): NotificationPipelineResult = mutex.withLock {
        val dedupKey = candidate.deduplicationKey()
        val existing = repository.getPinBySourceKey(dedupKey)
        val mapped = mapper.mapOrNull(candidate, existing?.id)
            ?: return@withLock NotificationPipelineResult.Ignored

        val event = mapped.copy(
            source = EventSource.NOTIFICATION,
            sourceKey = dedupKey,
        )

        val validated = when (val validation = TimelineEventValidator.validate(event, clockMillis())) {
            is ValidationResult.Valid -> validation.event
            is ValidationResult.Invalid -> {
                return@withLock NotificationPipelineResult.ValidationFailed(
                    validation.errors.map { it.message },
                )
            }
        }

        if (existing != null && existing.matchesContent(validated)) {
            return@withLock NotificationPipelineResult.Unchanged(pinId = existing.id)
        }

        when (val sendResult = pinSender.sendPin(validated)) {
            is PinSendResult.Success -> {
                repository.saveSentPin(
                    SentPinRecord.fromTimelineEvent(validated, sentAtMillis = clockMillis()),
                )
                NotificationPipelineResult.Delivered(pinId = sendResult.pinId)
            }
            else -> NotificationPipelineResult.DeliveryFailed(sendResult)
        }
    }

    suspend fun processRemoval(notificationKey: String): NotificationRemovalResult = mutex.withLock {
        val normalizedKey = notificationKey.trim()
        if (normalizedKey.isEmpty()) return@withLock NotificationRemovalResult.NotTracked

        val existing = repository.getPinBySourceKey(normalizedKey)
            ?: return@withLock NotificationRemovalResult.NotTracked

        when (val result = pinSender.deletePin(existing.id)) {
            is PinDeleteResult.Success, is PinDeleteResult.UnknownPin -> {
                repository.deletePinRecord(existing.id)
                NotificationRemovalResult.Deleted(pinId = existing.id)
            }
            else -> NotificationRemovalResult.DeleteFailed(result)
        }
    }

    private fun SentPinRecord.matchesContent(event: TimelineEvent): Boolean {
        return id == event.id &&
            title == event.title &&
            description == event.description &&
            startTimeMillis == event.startTimeMillis &&
            durationMinutes == event.durationMinutes &&
            iconUri == event.iconUri &&
            backgroundColor == event.backgroundColor &&
            layoutType == event.layoutType.name
    }
}

sealed interface NotificationPipelineResult {
    data object Ignored : NotificationPipelineResult
    data class Unchanged(val pinId: String) : NotificationPipelineResult
    data class ValidationFailed(val reasons: List<String>) : NotificationPipelineResult
    data class Delivered(val pinId: String) : NotificationPipelineResult
    data class DeliveryFailed(val result: PinSendResult) : NotificationPipelineResult
}

sealed interface NotificationRemovalResult {
    data object NotTracked : NotificationRemovalResult
    data class Deleted(val pinId: String) : NotificationRemovalResult
    data class DeleteFailed(val result: PinDeleteResult) : NotificationRemovalResult
}
