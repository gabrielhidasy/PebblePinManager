package xyz.hidasy.pebblepinmanager.data

import kotlinx.serialization.Serializable
import xyz.hidasy.pebblepinmanager.domain.EventSource
import xyz.hidasy.pebblepinmanager.domain.PinLayoutType
import xyz.hidasy.pebblepinmanager.domain.TimelineEvent

/**
 * Persisted record of a pin successfully sent to the Pebble Timeline.
 *
 * Storing full event metadata + pin ID enables recent-pin confirmation in the MVP UI,
 * in-place pin updates and deletes (#4), and notification deduplication (#7).
 */
@Serializable
data class SentPinRecord(
    val id: String,
    val title: String,
    val description: String? = null,
    val startTimeMillis: Long,
    val durationMinutes: Int? = null,
    val iconUri: String = TimelineEvent.DEFAULT_ICON_URI,
    val backgroundColor: String? = null,
    val layoutType: String = PinLayoutType.GENERIC_PIN.name,
    val source: String = EventSource.MANUAL.name,
    val sourceKey: String? = null,
    val sentAtMillis: Long,
) {
    fun toTimelineEvent(): TimelineEvent = TimelineEvent(
        id = id,
        title = title,
        description = description,
        startTimeMillis = startTimeMillis,
        durationMinutes = durationMinutes,
        iconUri = iconUri,
        backgroundColor = backgroundColor,
        layoutType = runCatching { PinLayoutType.valueOf(layoutType) }.getOrDefault(PinLayoutType.GENERIC_PIN),
        source = runCatching { EventSource.valueOf(source) }.getOrDefault(EventSource.MANUAL),
        sourceKey = sourceKey,
    )

    companion object {
        fun fromTimelineEvent(
            event: TimelineEvent,
            sentAtMillis: Long = System.currentTimeMillis(),
        ): SentPinRecord {
            val normalized = event.normalized()
            return SentPinRecord(
                id = normalized.id,
                title = normalized.title,
                description = normalized.description,
                startTimeMillis = normalized.startTimeMillis,
                durationMinutes = normalized.durationMinutes,
                iconUri = normalized.iconUri,
                backgroundColor = normalized.backgroundColor,
                layoutType = normalized.layoutType.name,
                source = normalized.source.name,
                sourceKey = normalized.sourceKey,
                sentAtMillis = sentAtMillis,
            )
        }
    }
}
