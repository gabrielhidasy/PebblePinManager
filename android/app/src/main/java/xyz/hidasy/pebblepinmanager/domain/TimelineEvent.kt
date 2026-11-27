package xyz.hidasy.pebblepinmanager.domain

import java.util.UUID

/**
 * Domain model representing a custom event to be delivered as a Pebble Timeline pin.
 *
 * Intentionally decoupled from both Jetpack Compose UI state and PebbleKit Android 2 models so
 * manual composer UI, local persistence (#4), and future Android Notification Access (#7) all
 * share the same canonical model.
 */
data class TimelineEvent(
    val id: String = generatePinId(),
    val title: String,
    val description: String? = null,
    val startTimeMillis: Long,
    val durationMinutes: Int? = null,
    val iconUri: String = DEFAULT_ICON_URI,
    val backgroundColor: String? = null,
    val primaryColor: String? = null,
    val secondaryColor: String? = null,
    val layoutType: PinLayoutType = PinLayoutType.GENERIC_PIN,
    val source: EventSource = EventSource.MANUAL,
    val sourceKey: String? = null,
) {
    /**
     * Returns a sanitized copy with trimmed text fields and blank optional strings normalized to null.
     */
    fun normalized(): TimelineEvent = copy(
        id = id.trim(),
        title = title.trim(),
        description = description?.trim()?.takeIf { it.isNotEmpty() },
        iconUri = iconUri.trim().ifEmpty { DEFAULT_ICON_URI },
        backgroundColor = backgroundColor?.trim()?.takeIf { it.isNotEmpty() },
        primaryColor = primaryColor?.trim()?.takeIf { it.isNotEmpty() },
        secondaryColor = secondaryColor?.trim()?.takeIf { it.isNotEmpty() },
        sourceKey = sourceKey?.trim()?.takeIf { it.isNotEmpty() },
    )

    companion object {
        const val DEFAULT_ICON_URI: String = "system://images/TIMELINE_CALENDAR"
        const val MAX_TITLE_LENGTH: Int = 128
        const val MAX_DESCRIPTION_LENGTH: Int = 512
        const val MIN_DURATION_MINUTES: Int = 1
        const val MAX_DURATION_MINUTES: Int = 1440

        fun generatePinId(prefix: String = "ppm"): String {
            val suffix = UUID.randomUUID().toString().replace("-", "").take(12)
            return "$prefix-$suffix"
        }
    }
}

/**
 * Supported top-level Pebble Timeline pin layouts.
 *
 * PebbleOS (`pin_db.c`) explicitly rejects `LayoutIdReminder` (`GENERIC_REMINDER`) and
 * `LayoutIdNotification` (`GENERIC_NOTIFICATION`) on top-level pins with `-EINVAL`.
 */
enum class PinLayoutType {
    GENERIC_PIN,
    CALENDAR_PIN,
}

enum class EventSource {
    MANUAL,
    NOTIFICATION,
    REMOTE_URL,
}
