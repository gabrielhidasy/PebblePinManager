package xyz.hidasy.pebblepinmanager.domain

import java.time.Duration

/**
 * Validates [TimelineEvent] values against Pebble Timeline constraints before transmission.
 *
 * Pebble Timeline supports pins within a rolling window of 2 days in the past up to 1 year in the
 * future, with a maximum body length of 512 characters.
 */
object TimelineEventValidator {
    val MAX_PAST_OFFSET: Duration = Duration.ofDays(2)
    val MAX_FUTURE_OFFSET: Duration = Duration.ofDays(365)

    fun validate(
        event: TimelineEvent,
        nowMillis: Long = System.currentTimeMillis(),
    ): ValidationResult {
        val normalized = event.normalized()
        val errors = buildList {
            if (normalized.id.isBlank()) {
                add(ValidationError.IdBlank)
            }
            if (normalized.title.isBlank()) {
                add(ValidationError.TitleBlank)
            } else if (normalized.title.length > TimelineEvent.MAX_TITLE_LENGTH) {
                add(
                    ValidationError.TitleTooLong(
                        actualLength = normalized.title.length,
                        maxLength = TimelineEvent.MAX_TITLE_LENGTH,
                    ),
                )
            }
            val descLength = normalized.description?.length ?: 0
            if (descLength > TimelineEvent.MAX_DESCRIPTION_LENGTH) {
                add(
                    ValidationError.DescriptionTooLong(
                        actualLength = descLength,
                        maxLength = TimelineEvent.MAX_DESCRIPTION_LENGTH,
                    ),
                )
            }
            val duration = normalized.durationMinutes
            if (duration != null &&
                (duration < TimelineEvent.MIN_DURATION_MINUTES || duration > TimelineEvent.MAX_DURATION_MINUTES)
            ) {
                add(
                    ValidationError.DurationOutOfRange(
                        durationMinutes = duration,
                        minMinutes = TimelineEvent.MIN_DURATION_MINUTES,
                        maxMinutes = TimelineEvent.MAX_DURATION_MINUTES,
                    ),
                )
            }
            if (!PinIcon.isValidSystemIconUri(normalized.iconUri)) {
                add(ValidationError.InvalidIconUri(normalized.iconUri))
            }
            val bgColor = normalized.backgroundColor
            if (bgColor != null && !PinAccentColor.isValidHexColor(bgColor)) {
                add(ValidationError.InvalidBackgroundColor(bgColor))
            }
            val earliestAllowed = nowMillis - MAX_PAST_OFFSET.toMillis()
            val latestAllowed = nowMillis + MAX_FUTURE_OFFSET.toMillis()
            if (normalized.startTimeMillis < earliestAllowed) {
                add(ValidationError.TimestampTooFarInPast)
            } else if (normalized.startTimeMillis > latestAllowed) {
                add(ValidationError.TimestampTooFarInFuture)
            }
        }

        return if (errors.isEmpty()) {
            ValidationResult.Valid(normalized)
        } else {
            ValidationResult.Invalid(errors)
        }
    }
}

sealed interface ValidationResult {
    data class Valid(val event: TimelineEvent) : ValidationResult
    data class Invalid(val errors: List<ValidationError>) : ValidationResult
}

sealed interface ValidationError {
    val message: String

    data object IdBlank : ValidationError {
        override val message: String = "Pin ID must not be blank."
    }

    data object TitleBlank : ValidationError {
        override val message: String = "Event title is required."
    }

    data class TitleTooLong(val actualLength: Int, val maxLength: Int) : ValidationError {
        override val message: String = "Title is too long ($actualLength/$maxLength characters)."
    }

    data class DescriptionTooLong(val actualLength: Int, val maxLength: Int) : ValidationError {
        override val message: String =
            "Description exceeds the Pebble Timeline limit ($actualLength/$maxLength characters)."
    }

    data class DurationOutOfRange(
        val durationMinutes: Int,
        val minMinutes: Int,
        val maxMinutes: Int,
    ) : ValidationError {
        override val message: String =
            "Duration must be between $minMinutes and $maxMinutes minutes (got $durationMinutes)."
    }

    data object TimestampTooFarInPast : ValidationError {
        override val message: String =
            "Pebble Timeline only accepts pins up to 2 days in the past."
    }

    data object TimestampTooFarInFuture : ValidationError {
        override val message: String =
            "Pebble Timeline only accepts pins up to 1 year in the future."
    }

    data class InvalidIconUri(val iconUri: String) : ValidationError {
        override val message: String =
            "Pin icon URI must match system://images/<ICON_NAME> (got '$iconUri')."
    }

    data class InvalidBackgroundColor(val backgroundColor: String) : ValidationError {
        override val message: String =
            "Background color must be a 6-digit hex color like #FF5500 (got '$backgroundColor')."
    }
}
