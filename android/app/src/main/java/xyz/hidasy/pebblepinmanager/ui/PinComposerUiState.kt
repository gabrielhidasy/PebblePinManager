package xyz.hidasy.pebblepinmanager.ui

import xyz.hidasy.pebblepinmanager.data.SentPinRecord
import xyz.hidasy.pebblepinmanager.domain.DurationParseResult
import xyz.hidasy.pebblepinmanager.domain.PinAccentColor
import xyz.hidasy.pebblepinmanager.domain.PinDurationFormatter
import xyz.hidasy.pebblepinmanager.domain.PinIcon
import xyz.hidasy.pebblepinmanager.domain.TimelineTimestampFormatter
import xyz.hidasy.pebblepinmanager.pebble.ConnectedPebbleWatch
import xyz.hidasy.pebblepinmanager.pebble.PebbleCompanionStatus
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

data class PinComposerUiState(
    val title: String = "",
    val description: String = "",
    val selectedDate: LocalDate,
    val selectedTime: LocalTime,
    val zoneId: ZoneId = ZoneId.systemDefault(),
    val selectedIcon: PinIcon = PinIcon.DEFAULT,
    val durationInput: String = "",
    val selectedBackgroundColor: PinAccentColor? = null,
    val titleError: String? = null,
    val descriptionError: String? = null,
    val timeError: String? = null,
    val durationError: String? = null,
    val companionStatus: PebbleCompanionStatus? = null,
    val connectedWatches: List<ConnectedPebbleWatch> = emptyList(),
    val isRunWithoutWatch: Boolean = false,
    val isSending: Boolean = false,
    val editingPin: SentPinRecord? = null,
    val deletingPinId: String? = null,
    val feedback: SendFeedback? = null,
    val feedbackEventId: Long = 0L,
    val sendConfirmation: PinSendConfirmation? = null,
    val sentPins: List<SentPinRecord> = emptyList(),
) {
    val editingPinId: String?
        get() = editingPin?.id

    val isEditing: Boolean
        get() = editingPin != null

    val selectedEpochMillis: Long
        get() = TimelineTimestampFormatter.toEpochMillis(selectedDate, selectedTime, zoneId)

    val selectedUtcIso8601: String
        get() = TimelineTimestampFormatter.toUtcIso8601(selectedEpochMillis)

    val formattedLocalPayloadTime: String
        get() = TimelineTimestampFormatter.formatLocalDateTimeWithZone(selectedEpochMillis, zoneId)

    val formattedLocalDate: String
        get() = TimelineTimestampFormatter.formatLocalDate(selectedDate)

    val formattedLocalTime: String
        get() = TimelineTimestampFormatter.formatLocalTime(selectedTime)

    val formattedZoneLabel: String
        get() = TimelineTimestampFormatter.formatZoneLabel(selectedEpochMillis, zoneId)

    val parsedDurationMinutes: Int?
        get() = (PinDurationFormatter.parseDurationInput(durationInput) as? DurationParseResult.Valid)?.minutes

    /**
     * Enables the `backgroundColor` picker when either a color-screen watch (`basalt`, `chalk`,
     * `emery`, `gabbro`) is connected or no watch is currently connected (queuing offline).
     */
    val supportsColorBackground: Boolean
        get() = connectedWatches.isEmpty() || connectedWatches.any { it.platform.supportsColorTimeline }

    /**
     * Background color actually attached to outgoing pins (omitted if only monochrome watches
     * are connected).
     */
    val effectiveBackgroundColor: PinAccentColor?
        get() = if (supportsColorBackground) selectedBackgroundColor else null
}

enum class PinDeliveryStatus {
    /** Delivered via Pebble companion app with one or more connected Pebble watches. */
    SENT_TO_WATCH,

    /** Queued in the Pebble companion app while no watch is currently connected. */
    QUEUED_FOR_WATCH,

    /** Captured locally in "Run without a watch" simulated timeline mode. */
    SIMULATED,
}

enum class PinConfirmationAction {
    CREATED,
    UPDATED,
}

data class PinSendConfirmation(
    val eventId: Long,
    val pinId: String,
    val title: String,
    val formattedWhen: String,
    val durationLabel: String? = null,
    val action: PinConfirmationAction,
    val deliveryStatus: PinDeliveryStatus,
    val connectedWatchSummary: String? = null,
    val isAutoPin: Boolean = false,
    val headline: String,
    val snackbarTitle: String,
    val snackbarDetails: String,
    val buttonLabel: String,
)

sealed interface SendFeedback {
    val headline: String
    val details: String

    data class Success(
        val pinId: String,
        override val headline: String,
        override val details: String,
    ) : SendFeedback

    data class Error(
        override val headline: String,
        override val details: String,
    ) : SendFeedback
}
