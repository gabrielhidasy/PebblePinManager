package xyz.hidasy.pebblepinmanager.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

class TimelineTimestampFormatterTest {
    @Test
    fun `converts New York winter local time to strict UTC ISO-8601`() {
        val epochMillis = TimelineTimestampFormatter.toEpochMillis(
            date = LocalDate.of(2026, 11, 21),
            time = LocalTime.of(14, 30),
            zoneId = ZoneId.of("America/New_York"),
        )

        assertEquals("2026-11-21T19:30:00Z", TimelineTimestampFormatter.toUtcIso8601(epochMillis))
    }

    @Test
    fun `converts New York summer DST local time to strict UTC ISO-8601`() {
        val epochMillis = TimelineTimestampFormatter.toEpochMillis(
            date = LocalDate.of(2026, 7, 15),
            time = LocalTime.of(9, 15),
            zoneId = ZoneId.of("America/New_York"),
        )

        assertEquals("2026-07-15T13:15:00Z", TimelineTimestampFormatter.toUtcIso8601(epochMillis))
    }

    @Test
    fun `converts positive offset timezone crossing date boundary to UTC`() {
        val epochMillis = TimelineTimestampFormatter.toEpochMillis(
            date = LocalDate.of(2026, 11, 22),
            time = LocalTime.of(1, 5),
            zoneId = ZoneId.of("Asia/Tokyo"),
        )

        assertEquals("2026-11-21T16:05:00Z", TimelineTimestampFormatter.toUtcIso8601(epochMillis))
    }

    @Test
    fun `formats local datetime with timezone abbreviation and offset`() {
        val zone = ZoneId.of("Europe/Budapest")
        val epochMillis = TimelineTimestampFormatter.toEpochMillis(
            date = LocalDate.of(2026, 11, 21),
            time = LocalTime.of(18, 45),
            zoneId = zone,
        )

        val formatted = TimelineTimestampFormatter.formatLocalDateTimeWithZone(
            epochMillis = epochMillis,
            zoneId = zone,
            locale = Locale.US,
        )

        assertTrue(formatted.contains("Nov 21, 2026 at 18:45"))
        assertTrue(formatted.contains("UTC+01:00"))
    }
}
