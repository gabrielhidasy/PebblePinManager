package xyz.hidasy.pebblepinmanager.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TimelineEventValidatorTest {
    private val fixedNow = Instant.parse("2026-11-21T12:00:00Z").toEpochMilli()

    @Test
    fun `valid event normalizes whitespace and blank description`() {
        val event = TimelineEvent(
            id = "  ppm-123  ",
            title = "  Team Standup  ",
            description = "   ",
            startTimeMillis = fixedNow + 3_600_000L,
            durationMinutes = 30,
        )

        val result = TimelineEventValidator.validate(event, nowMillis = fixedNow)
        assertTrue(result is ValidationResult.Valid)
        val valid = (result as ValidationResult.Valid).event
        assertEquals("ppm-123", valid.id)
        assertEquals("Team Standup", valid.title)
        assertNull(valid.description)
        assertEquals(30, valid.durationMinutes)
    }

    @Test
    fun `blank title fails validation`() {
        val event = TimelineEvent(
            id = "ppm-1",
            title = "   ",
            startTimeMillis = fixedNow + 60_000L,
        )

        val result = TimelineEventValidator.validate(event, nowMillis = fixedNow)
        assertTrue(result is ValidationResult.Invalid)
        val errors = (result as ValidationResult.Invalid).errors
        assertTrue(errors.contains(ValidationError.TitleBlank))
    }

    @Test
    fun `title exceeding max length fails validation`() {
        val event = TimelineEvent(
            id = "ppm-2",
            title = "A".repeat(TimelineEvent.MAX_TITLE_LENGTH + 1),
            startTimeMillis = fixedNow + 60_000L,
        )

        val result = TimelineEventValidator.validate(event, nowMillis = fixedNow)
        assertTrue(result is ValidationResult.Invalid)
        val errors = (result as ValidationResult.Invalid).errors
        assertTrue(errors.any { it is ValidationError.TitleTooLong })
    }

    @Test
    fun `description exceeding 512 chars fails validation`() {
        val event = TimelineEvent(
            id = "ppm-3",
            title = "Flight",
            description = "X".repeat(TimelineEvent.MAX_DESCRIPTION_LENGTH + 1),
            startTimeMillis = fixedNow + 60_000L,
        )

        val result = TimelineEventValidator.validate(event, nowMillis = fixedNow)
        assertTrue(result is ValidationResult.Invalid)
        val errors = (result as ValidationResult.Invalid).errors
        assertTrue(errors.any { it is ValidationError.DescriptionTooLong })
    }

    @Test
    fun `timestamp more than 2 days in past fails validation`() {
        val tooOld = fixedNow - TimelineEventValidator.MAX_PAST_OFFSET.toMillis() - 60_000L
        val event = TimelineEvent(
            id = "ppm-4",
            title = "Old Event",
            startTimeMillis = tooOld,
        )

        val result = TimelineEventValidator.validate(event, nowMillis = fixedNow)
        assertTrue(result is ValidationResult.Invalid)
        assertTrue((result as ValidationResult.Invalid).errors.contains(ValidationError.TimestampTooFarInPast))
    }

    @Test
    fun `timestamp more than 1 year in future fails validation`() {
        val tooFar = fixedNow + TimelineEventValidator.MAX_FUTURE_OFFSET.toMillis() + 60_000L
        val event = TimelineEvent(
            id = "ppm-5",
            title = "Far Future",
            startTimeMillis = tooFar,
        )

        val result = TimelineEventValidator.validate(event, nowMillis = fixedNow)
        assertTrue(result is ValidationResult.Invalid)
        assertTrue((result as ValidationResult.Invalid).errors.contains(ValidationError.TimestampTooFarInFuture))
    }

    @Test
    fun `duration outside allowed bounds fails validation`() {
        val zeroDuration = TimelineEvent(
            id = "ppm-6",
            title = "Zero Meeting",
            startTimeMillis = fixedNow + 60_000L,
            durationMinutes = 0,
        )
        val tooLongDuration = TimelineEvent(
            id = "ppm-7",
            title = "Multi-day Meeting",
            startTimeMillis = fixedNow + 60_000L,
            durationMinutes = TimelineEvent.MAX_DURATION_MINUTES + 1,
        )

        val zeroResult = TimelineEventValidator.validate(zeroDuration, nowMillis = fixedNow)
        assertTrue(zeroResult is ValidationResult.Invalid)
        assertTrue((zeroResult as ValidationResult.Invalid).errors.any { it is ValidationError.DurationOutOfRange })

        val longResult = TimelineEventValidator.validate(tooLongDuration, nowMillis = fixedNow)
        assertTrue(longResult is ValidationResult.Invalid)
        assertTrue((longResult as ValidationResult.Invalid).errors.any { it is ValidationError.DurationOutOfRange })
    }

    @Test
    fun `custom icon URI and background color pass validation when well-formed`() {
        val event = TimelineEvent(
            id = "ppm-8",
            title = "Game Night",
            startTimeMillis = fixedNow + 60_000L,
            durationMinutes = 90,
            iconUri = PinIcon.TIMELINE_SPORTS.uri,
            backgroundColor = PinAccentColor.SKY.hexCode,
        )

        val result = TimelineEventValidator.validate(event, nowMillis = fixedNow)
        assertTrue(result is ValidationResult.Valid)
        val valid = (result as ValidationResult.Valid).event
        assertEquals("system://images/TIMELINE_SPORTS", valid.iconUri)
        assertEquals("#00AAFF", valid.backgroundColor)
        assertEquals(90, valid.durationMinutes)
    }

    @Test
    fun `malformed icon URI and background color fail validation`() {
        val event = TimelineEvent(
            id = "ppm-9",
            title = "Invalid Appearance",
            startTimeMillis = fixedNow + 60_000L,
            iconUri = "not-a-pebble-icon",
            backgroundColor = "not-a-hex",
        )

        val result = TimelineEventValidator.validate(event, nowMillis = fixedNow)
        assertTrue(result is ValidationResult.Invalid)
        val errors = (result as ValidationResult.Invalid).errors
        assertTrue(errors.any { it is ValidationError.InvalidIconUri })
        assertTrue(errors.any { it is ValidationError.InvalidBackgroundColor })
    }
}
