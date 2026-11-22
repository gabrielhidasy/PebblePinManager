package xyz.hidasy.pebblepinmanager.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Timezone-safe date/time conversion and formatting helpers for Pebble Timeline pins.
 *
 * Pebble Timeline pins are transmitted as UTC instants (ISO-8601 UTC or epoch millis), while users
 * pick dates and times in their local timezone.
 */
object TimelineTimestampFormatter {
    private val localDateFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.US)

    private val localTimeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    private val localDateTimeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE, MMM d, yyyy 'at' HH:mm", Locale.US)

    /**
     * Combines a local [date] and [time] in [zoneId] into UTC epoch milliseconds (truncated to seconds).
     */
    fun toEpochMillis(
        date: LocalDate,
        time: LocalTime,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Long {
        val localDateTime = LocalDateTime.of(date, time.truncatedTo(ChronoUnit.MINUTES))
        return localDateTime.atZone(zoneId).toInstant().toEpochMilli()
    }

    /**
     * Formats [epochMillis] as a strict UTC ISO-8601 string (e.g. `2026-11-21T15:30:00Z`).
     */
    fun toUtcIso8601(epochMillis: Long): String {
        val instant = Instant.ofEpochMilli(epochMillis).truncatedTo(ChronoUnit.SECONDS)
        return DateTimeFormatter.ISO_INSTANT.format(instant)
    }

    /**
     * Formats a [LocalDate] for the date picker button.
     */
    fun formatLocalDate(date: LocalDate, locale: Locale = Locale.getDefault()): String {
        val formatter = if (locale == Locale.US) {
            localDateFormatter
        } else {
            DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", locale)
        }
        return date.format(formatter)
    }

    /**
     * Formats a [LocalTime] in 24-hour `HH:mm` format.
     */
    fun formatLocalTime(time: LocalTime): String = time.format(localTimeFormatter)

    /**
     * Formats [epochMillis] in [zoneId] including timezone abbreviation and UTC offset, e.g.:
     * `Sat, Nov 21, 2026 at 14:30 (EST, UTC-05:00)`.
     */
    fun formatLocalDateTimeWithZone(
        epochMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        val zoned = Instant.ofEpochMilli(epochMillis).atZone(zoneId)
        val baseFormatter = if (locale == Locale.US) {
            localDateTimeFormatter
        } else {
            DateTimeFormatter.ofPattern("EEE, MMM d, yyyy 'at' HH:mm", locale)
        }
        val zoneShort = zoneId.getDisplayName(TextStyle.SHORT, locale)
        val offset = formatOffset(zoned.offset)
        return "${zoned.format(baseFormatter)} ($zoneShort, $offset)"
    }

    fun formatZoneLabel(
        epochMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        val zoned = Instant.ofEpochMilli(epochMillis).atZone(zoneId)
        val zoneShort = zoneId.getDisplayName(TextStyle.SHORT, locale)
        return "$zoneShort (${formatOffset(zoned.offset)})"
    }

    private fun formatOffset(offset: ZoneOffset): String {
        return if (offset == ZoneOffset.UTC) {
            "UTC+00:00"
        } else {
            "UTC${offset.id}"
        }
    }
}
