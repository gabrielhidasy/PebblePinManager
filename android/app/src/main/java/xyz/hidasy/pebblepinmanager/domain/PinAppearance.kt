package xyz.hidasy.pebblepinmanager.domain

/**
 * Curated catalog of Pebble Timeline system icons (`tinyIcon` / `system://images/<NAME>`).
 *
 * Kept pure Kotlin (no Android/Compose dependencies) so domain models, validation, persistence,
 * and unit tests remain platform-agnostic.
 */
enum class PinIcon(
    val systemName: String,
    val label: String,
) {
    TIMELINE_CALENDAR("TIMELINE_CALENDAR", "Calendar"),
    NOTIFICATION_FLAG("NOTIFICATION_FLAG", "Flag"),
    TIMELINE_SPORTS("TIMELINE_SPORTS", "Sports"),
    GENERIC_CONFIRMATION("GENERIC_CONFIRMATION", "Checkmark"),
    TIMELINE_WEATHER("TIMELINE_WEATHER", "Weather"),
    AUDIO_CASSETTE("AUDIO_CASSETTE", "Audio"),
    ALARM_CLOCK("ALARM_CLOCK", "Alarm"),
    DINNER_RESERVATION("DINNER_RESERVATION", "Dining"),
    SCHEDULED_FLIGHT("SCHEDULED_FLIGHT", "Flight"),
    CAR_RENTAL("CAR_RENTAL", "Travel"),
    NOTIFICATION_REMINDER("NOTIFICATION_REMINDER", "Reminder"),
    LOCATION("LOCATION", "Location"),
    BIRTHDAY_EVENT("BIRTHDAY_EVENT", "Birthday"),
    MOVIE_EVENT("MOVIE_EVENT", "Movie"),
    MUSIC_EVENT("MUSIC_EVENT", "Music"),
    GENERIC_EMAIL("GENERIC_EMAIL", "Mail"),
    ;

    val uri: String
        get() = "$SYSTEM_IMAGE_PREFIX$systemName"

    companion object {
        const val SYSTEM_IMAGE_PREFIX: String = "system://images/"
        val DEFAULT: PinIcon = TIMELINE_CALENDAR

        private val VALID_URI_REGEX: Regex = Regex("^system://images/[A-Z0-9_]+$")

        fun isValidSystemIconUri(uri: String): Boolean = VALID_URI_REGEX.matches(uri.trim())

        fun findByUri(uri: String?): PinIcon? {
            val trimmed = uri?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val normalizedName = trimmed.removePrefix(SYSTEM_IMAGE_PREFIX)
            return entries.firstOrNull {
                it.uri.equals(trimmed, ignoreCase = true) ||
                    it.systemName.equals(normalizedName, ignoreCase = true)
            }
        }

        fun fromUri(uri: String?): PinIcon = findByUri(uri) ?: DEFAULT
    }
}

/**
 * Curated subset of Pebble's 64-color hardware palette (2-bit per RGB channel: 00/55/AA/FF)
 * for optional pin `backgroundColor` accents on color-screen watches (`basalt`, `chalk`,
 * `emery`, `gabbro`).
 */
enum class PinAccentColor(
    val label: String,
    val hexCode: String,
    val argb: Long,
) {
    ORANGE("Orange", "#FF5500", 0xFFFF5500L),
    RED("Red", "#FF0000", 0xFFFF0000L),
    SUNSET("Sunset", "#FF5555", 0xFFFF5555L),
    YELLOW("Yellow", "#FFAA00", 0xFFFFAA00L),
    GREEN("Green", "#00AA00", 0xFF00AA00L),
    TEAL("Teal", "#00AAAA", 0xFF00AAAAL),
    SKY("Sky Blue", "#00AAFF", 0xFF00AAFFL),
    COBALT("Cobalt", "#0055AA", 0xFF0055AAL),
    INDIGO("Indigo", "#5555FF", 0xFF5555FFL),
    PURPLE("Purple", "#AA55FF", 0xFFAA55FFL),
    ROSE("Rose", "#FF55AA", 0xFFFF55AAL),
    DARK_GRAY("Dark Gray", "#555555", 0xFF555555L),
    ;

    companion object {
        private val HEX_COLOR_REGEX: Regex = Regex("^#?[0-9A-Fa-f]{6}$")

        fun isValidHexColor(color: String): Boolean = HEX_COLOR_REGEX.matches(color.trim())

        fun fromHex(hex: String?): PinAccentColor? {
            val clean = hex?.trim()?.removePrefix("#")?.uppercase()?.takeIf { it.length == 6 }
                ?: return null
            return entries.firstOrNull { it.hexCode.removePrefix("#") == clean }
        }
    }
}

/**
 * Parsing and formatting helpers for optional Pebble Timeline pin durations (in minutes).
 */
object PinDurationFormatter {
    val PRESET_MINUTES: List<Int> = listOf(15, 30, 60, 120)

    fun parseDurationInput(raw: String): DurationParseResult {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return DurationParseResult.None
        }
        val parsed = trimmed.toIntOrNull()
            ?: return DurationParseResult.Invalid(
                "Duration must be a whole number of minutes (${TimelineEvent.MIN_DURATION_MINUTES}–${TimelineEvent.MAX_DURATION_MINUTES}).",
            )
        if (parsed < TimelineEvent.MIN_DURATION_MINUTES || parsed > TimelineEvent.MAX_DURATION_MINUTES) {
            return DurationParseResult.Invalid(
                "Duration must be between ${TimelineEvent.MIN_DURATION_MINUTES} and ${TimelineEvent.MAX_DURATION_MINUTES} minutes (got $parsed).",
            )
        }
        return DurationParseResult.Valid(parsed)
    }

    fun formatMinutes(durationMinutes: Int): String {
        val hours = durationMinutes / 60
        val mins = durationMinutes % 60
        return when {
            hours == 0 -> "$mins min"
            mins == 0 -> "${hours}h"
            else -> "${hours}h ${mins}m"
        }
    }
}

sealed interface DurationParseResult {
    data object None : DurationParseResult
    data class Valid(val minutes: Int) : DurationParseResult
    data class Invalid(val message: String) : DurationParseResult
}
