package xyz.hidasy.pebblepinmanager.notifications

import xyz.hidasy.pebblepinmanager.domain.TimelineEventValidator
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Pure-Kotlin on-device extractor that detects event timestamps inside notification text
 * (e.g., delivery windows, flight departure times, calendar reminders) with zero network access.
 */
object NotificationTimeExtractor {
    private const val ROLLOVER_GRACE_MINUTES: Long = 5L

    // e.g. 2026-11-22 14:30, 2026-11-22T14:30, 2026-11-22 2:30 PM
    private val ISO_DATE_TIME_REGEX = Regex(
        """\b(\d{4})-(\d{2})-(\d{2})[T\s]+(\d{1,2})(?::([0-5]\d))?(?:\s*(a\.?m\.?|p\.?m\.?))?\b""",
        RegexOption.IGNORE_CASE,
    )

    // e.g. "in 1h 30m", "in 2 hours 15 min", "in 45 minutes", "in 20 min", "in 2 hrs"
    private val RELATIVE_HOURS_AND_MINUTES_REGEX = Regex(
        """\bin\s+(\d{1,2})\s*(?:hours?|hrs?|h)\s*(?:and\s*)?(\d{1,2})\s*(?:minutes?|mins?|m)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val RELATIVE_HOURS_REGEX = Regex(
        """\bin\s+(\d{1,3})\s*(?:hours?|hrs?|h)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val RELATIVE_MINUTES_REGEX = Regex(
        """\bin\s+(\d{1,4})\s*(?:minutes?|mins?|m)\b""",
        RegexOption.IGNORE_CASE,
    )

    // e.g. "tomorrow at 9:00", "tomorrow 2:30 PM", "today at 18:15"
    private val DAY_KEYWORD_12H_REGEX = Regex(
        """\b(today|tomorrow)\s+(?:at\s+)?(1[0-2]|0?[1-9])(?::([0-5]\d))?\s*(a\.?m\.?|p\.?m\.?)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val DAY_KEYWORD_24H_REGEX = Regex(
        """\b(today|tomorrow)\s+(?:at\s+)?([01]?\d|2[0-3]):([0-5]\d)\b""",
        RegexOption.IGNORE_CASE,
    )

    // e.g. "2:30 PM", "9 AM", "12:15 p.m."
    private val CLOCK_12H_REGEX = Regex(
        """\b(1[0-2]|0?[1-9])(?::([0-5]\d))?\s*(a\.?m\.?|p\.?m\.?)\b""",
        RegexOption.IGNORE_CASE,
    )

    // e.g. "14:30", "09:05" (avoiding version/ratio strings like 1.2:3 or 10:20:30 seconds-only tokens)
    private val CLOCK_24H_REGEX = Regex(
        """(?<![\d.:])([01]?\d|2[0-3]):([0-5]\d)(?![\d:])""",
    )

    /**
     * Attempts to extract a Pebble-valid UTC epoch timestamp from [text].
     *
     * When [customTimeRegex] is provided and valid, extraction runs against its first capturing
     * group (or full match) before falling back to `null` if unparseable.
     */
    fun extractEventTimeMillis(
        text: String,
        referenceMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
        customTimeRegex: String? = null,
    ): Long? {
        if (text.isBlank()) return null

        val targetText = if (!customTimeRegex.isNullOrBlank()) {
            val regex = runCatching { Regex(customTimeRegex.trim(), RegexOption.IGNORE_CASE) }
                .getOrNull() ?: return null
            val match = regex.find(text) ?: return null
            extractGroupOrFullMatch(match) ?: return null
        } else {
            text
        }

        val referenceZoned = Instant.ofEpochMilli(referenceMillis).atZone(zoneId)
        val parsed = parseStructuredTimestamp(targetText, referenceZoned) ?: return null
        return parsed.takeIf { isWithinTimelineWindow(it, referenceMillis) }
    }

    private fun parseStructuredTimestamp(
        text: String,
        referenceZoned: ZonedDateTime,
    ): Long? {
        // 1. Explicit ISO date + time
        ISO_DATE_TIME_REGEX.find(text)?.let { match ->
            val year = match.groupValues[1].toIntOrNull()
            val month = match.groupValues[2].toIntOrNull()
            val day = match.groupValues[3].toIntOrNull()
            val rawHour = match.groupValues[4].toIntOrNull()
            val minute = match.groupValues[5].toIntOrNull() ?: 0
            val amPm = match.groupValues[6].takeIf { it.isNotBlank() }

            if (year != null && month != null && day != null && rawHour != null) {
                val hour24 = resolveHour(rawHour, amPm)
                if (hour24 != null) {
                    val millis = runCatching {
                        val date = LocalDate.of(year, month, day)
                        val time = LocalTime.of(hour24, minute)
                        ZonedDateTime.of(date, time, referenceZoned.zone).toInstant().toEpochMilli()
                    }.getOrNull()
                    if (millis != null) return millis
                }
            }
        }

        // 2. Relative offsets ("in 1h 30m", "in 2 hours", "in 45 min")
        RELATIVE_HOURS_AND_MINUTES_REGEX.find(text)?.let { match ->
            val hours = match.groupValues[1].toLongOrNull() ?: return@let
            val minutes = match.groupValues[2].toLongOrNull() ?: return@let
            return referenceZoned
                .plusHours(hours)
                .plusMinutes(minutes)
                .truncatedTo(ChronoUnit.MINUTES)
                .toInstant()
                .toEpochMilli()
        }
        RELATIVE_HOURS_REGEX.find(text)?.let { match ->
            val hours = match.groupValues[1].toLongOrNull() ?: return@let
            return referenceZoned
                .plusHours(hours)
                .truncatedTo(ChronoUnit.MINUTES)
                .toInstant()
                .toEpochMilli()
        }
        RELATIVE_MINUTES_REGEX.find(text)?.let { match ->
            val minutes = match.groupValues[1].toLongOrNull() ?: return@let
            return referenceZoned
                .plusMinutes(minutes)
                .truncatedTo(ChronoUnit.MINUTES)
                .toInstant()
                .toEpochMilli()
        }

        // 3. Day keyword ("today" / "tomorrow") + 12h or 24h time
        DAY_KEYWORD_12H_REGEX.find(text)?.let { match ->
            val dayOffset = if (match.groupValues[1].equals("tomorrow", ignoreCase = true)) 1L else 0L
            val hour12 = match.groupValues[2].toIntOrNull() ?: return@let
            val minute = match.groupValues[3].toIntOrNull() ?: 0
            val amPm = match.groupValues[4]
            val hour24 = resolveHour(hour12, amPm) ?: return@let
            val date = referenceZoned.toLocalDate().plusDays(dayOffset)
            return ZonedDateTime.of(date, LocalTime.of(hour24, minute), referenceZoned.zone)
                .toInstant()
                .toEpochMilli()
        }
        DAY_KEYWORD_24H_REGEX.find(text)?.let { match ->
            val dayOffset = if (match.groupValues[1].equals("tomorrow", ignoreCase = true)) 1L else 0L
            val hour24 = match.groupValues[2].toIntOrNull() ?: return@let
            val minute = match.groupValues[3].toIntOrNull() ?: return@let
            val date = referenceZoned.toLocalDate().plusDays(dayOffset)
            return ZonedDateTime.of(date, LocalTime.of(hour24, minute), referenceZoned.zone)
                .toInstant()
                .toEpochMilli()
        }

        // 4. Bare 12-hour clock time (rolls over to tomorrow if >5 min in the past)
        CLOCK_12H_REGEX.find(text)?.let { match ->
            val hour12 = match.groupValues[1].toIntOrNull() ?: return@let
            val minute = match.groupValues[2].toIntOrNull() ?: 0
            val amPm = match.groupValues[3]
            val hour24 = resolveHour(hour12, amPm) ?: return@let
            return resolveWithSmartRollover(LocalTime.of(hour24, minute), referenceZoned)
        }

        // 5. Bare 24-hour clock time (rolls over to tomorrow if >5 min in the past)
        CLOCK_24H_REGEX.find(text)?.let { match ->
            val hour24 = match.groupValues[1].toIntOrNull() ?: return@let
            val minute = match.groupValues[2].toIntOrNull() ?: return@let
            return resolveWithSmartRollover(LocalTime.of(hour24, minute), referenceZoned)
        }

        return null
    }

    private fun resolveWithSmartRollover(
        time: LocalTime,
        referenceZoned: ZonedDateTime,
    ): Long {
        var candidate = ZonedDateTime.of(referenceZoned.toLocalDate(), time, referenceZoned.zone)
        if (candidate.isBefore(referenceZoned.minusMinutes(ROLLOVER_GRACE_MINUTES))) {
            candidate = candidate.plusDays(1)
        }
        return candidate.toInstant().toEpochMilli()
    }

    private fun resolveHour(rawHour: Int, amPm: String?): Int? {
        if (amPm.isNullOrBlank()) {
            return rawHour.takeIf { it in 0..23 }
        }
        if (rawHour !in 1..12) return null
        val isPm = amPm.lowercase().startsWith("p")
        return when {
            isPm && rawHour == 12 -> 12
            isPm -> rawHour + 12
            rawHour == 12 -> 0
            else -> rawHour
        }
    }

    private fun extractGroupOrFullMatch(match: MatchResult): String? {
        val group1 = match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
        return group1 ?: match.value.trim().takeIf { it.isNotEmpty() }
    }

    private fun isWithinTimelineWindow(candidateMillis: Long, referenceMillis: Long): Boolean {
        val earliest = referenceMillis - TimelineEventValidator.MAX_PAST_OFFSET.toMillis()
        val latest = referenceMillis + TimelineEventValidator.MAX_FUTURE_OFFSET.toMillis()
        return candidateMillis in earliest..latest
    }
}
