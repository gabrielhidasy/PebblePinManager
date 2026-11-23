package xyz.hidasy.pebblepinmanager.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class NotificationTimeExtractorTest {
    private val utc = ZoneId.of("UTC")
    private val referenceMillis = Instant.parse("2026-11-22T14:00:00Z").toEpochMilli()

    @Test
    fun `extracts explicit ISO date and time`() {
        val extracted = NotificationTimeExtractor.extractEventTimeMillis(
            text = "Flight LH400 departs 2026-11-24 16:45 from Terminal 2",
            referenceMillis = referenceMillis,
            zoneId = utc,
        )
        assertEquals(Instant.parse("2026-11-24T16:45:00Z").toEpochMilli(), extracted)
    }

    @Test
    fun `extracts ISO date with 12-hour PM suffix`() {
        val extracted = NotificationTimeExtractor.extractEventTimeMillis(
            text = "Reservation confirmed for 2026-11-25 7:30 PM",
            referenceMillis = referenceMillis,
            zoneId = utc,
        )
        assertEquals(Instant.parse("2026-11-25T19:30:00Z").toEpochMilli(), extracted)
    }

    @Test
    fun `extracts relative minute and hour offsets`() {
        val mins = NotificationTimeExtractor.extractEventTimeMillis(
            text = "Courier arriving in 25 minutes",
            referenceMillis = referenceMillis,
            zoneId = utc,
        )
        assertEquals(Instant.parse("2026-11-22T14:25:00Z").toEpochMilli(), mins)

        val hoursAndMins = NotificationTimeExtractor.extractEventTimeMillis(
            text = "Boarding starts in 1h 30m at Gate B4",
            referenceMillis = referenceMillis,
            zoneId = utc,
        )
        assertEquals(Instant.parse("2026-11-22T15:30:00Z").toEpochMilli(), hoursAndMins)

        val hoursOnly = NotificationTimeExtractor.extractEventTimeMillis(
            text = "Meeting begins in 2 hours",
            referenceMillis = referenceMillis,
            zoneId = utc,
        )
        assertEquals(Instant.parse("2026-11-22T16:00:00Z").toEpochMilli(), hoursOnly)
    }

    @Test
    fun `extracts tomorrow and today day qualifiers with 12h and 24h clocks`() {
        val tomorrow = NotificationTimeExtractor.extractEventTimeMillis(
            text = "Dentist reminder: tomorrow at 9:15 AM",
            referenceMillis = referenceMillis,
            zoneId = utc,
        )
        assertEquals(Instant.parse("2026-11-23T09:15:00Z").toEpochMilli(), tomorrow)

        val today24h = NotificationTimeExtractor.extractEventTimeMillis(
            text = "Package arrives today 18:45",
            referenceMillis = referenceMillis,
            zoneId = utc,
        )
        assertEquals(Instant.parse("2026-11-22T18:45:00Z").toEpochMilli(), today24h)
    }

    @Test
    fun `rolls bare clock time forward to next day when more than 5 minutes in the past`() {
        val morningFlight = NotificationTimeExtractor.extractEventTimeMillis(
            text = "Check-in open! Flight boards at 07:30",
            referenceMillis = referenceMillis, // 14:00 UTC
            zoneId = utc,
        )
        assertEquals(Instant.parse("2026-11-23T07:30:00Z").toEpochMilli(), morningFlight)

        // Within 5-minute grace window keeps today
        val justStarted = NotificationTimeExtractor.extractEventTimeMillis(
            text = "Standup at 13:57",
            referenceMillis = referenceMillis, // 14:00 UTC
            zoneId = utc,
        )
        assertEquals(Instant.parse("2026-11-22T13:57:00Z").toEpochMilli(), justStarted)
    }

    @Test
    fun `respects local timezone when converting extracted wall-clock time to UTC`() {
        val newYork = ZoneId.of("America/New_York") // EST = UTC-5 in November
        val refNewYork = ZonedDateTime.of(2026, 11, 22, 9, 0, 0, 0, newYork).toInstant().toEpochMilli()

        val extracted = NotificationTimeExtractor.extractEventTimeMillis(
            text = "Lunch with Alex at 1:30 PM",
            referenceMillis = refNewYork,
            zoneId = newYork,
        )
        val expected = ZonedDateTime.of(2026, 11, 22, 13, 30, 0, 0, newYork).toInstant().toEpochMilli()
        assertEquals(expected, extracted)
    }

    @Test
    fun `supports custom time extraction regex with capturing group`() {
        val extracted = NotificationTimeExtractor.extractEventTimeMillis(
            text = "Updated at 14:05 — New departure: 19:20",
            referenceMillis = referenceMillis,
            zoneId = utc,
            customTimeRegex = """departure:\s*(\d{1,2}:\d{2})""",
        )
        assertEquals(Instant.parse("2026-11-22T19:20:00Z").toEpochMilli(), extracted)
    }

    @Test
    fun `returns null when no valid time or out of Pebble timeline window`() {
        assertNull(
            NotificationTimeExtractor.extractEventTimeMillis(
                text = "Your order has shipped via courier",
                referenceMillis = referenceMillis,
                zoneId = utc,
            ),
        )
        assertNull(
            NotificationTimeExtractor.extractEventTimeMillis(
                text = "Archive event 2020-01-01 10:00",
                referenceMillis = referenceMillis,
                zoneId = utc,
            ),
        )
        assertNull(
            NotificationTimeExtractor.extractEventTimeMillis(
                text = "Meeting at 15:00",
                referenceMillis = referenceMillis,
                zoneId = utc,
                customTimeRegex = "[unclosed-regex",
            ),
        )
    }
}
