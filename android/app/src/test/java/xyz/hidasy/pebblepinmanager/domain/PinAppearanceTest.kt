package xyz.hidasy.pebblepinmanager.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinAppearanceTest {
    @Test
    fun `pin icon catalog includes required Pebble system icons with valid URIs`() {
        val names = PinIcon.entries.map { it.systemName }.toSet()
        assertTrue("TIMELINE_CALENDAR" in names)
        assertTrue("NOTIFICATION_FLAG" in names)
        assertTrue("TIMELINE_SPORTS" in names)
        assertTrue("GENERIC_CONFIRMATION" in names)
        assertTrue("TIMELINE_WEATHER" in names)
        assertTrue("AUDIO_CASSETTE" in names)

        PinIcon.entries.forEach { icon ->
            assertEquals("system://images/${icon.systemName}", icon.uri)
            assertTrue(PinIcon.isValidSystemIconUri(icon.uri))
        }
    }

    @Test
    fun `pin icon lookup resolves full URIs, bare names, and falls back to default`() {
        assertEquals(PinIcon.NOTIFICATION_FLAG, PinIcon.fromUri("system://images/NOTIFICATION_FLAG"))
        assertEquals(PinIcon.TIMELINE_SPORTS, PinIcon.fromUri("timeline_sports"))
        assertEquals(PinIcon.AUDIO_CASSETTE, PinIcon.findByUri("  system://images/audio_cassette  "))
        assertNull(PinIcon.findByUri("system://images/NON_EXISTENT"))
        assertEquals(PinIcon.DEFAULT, PinIcon.fromUri("system://images/NON_EXISTENT"))
        assertEquals(PinIcon.DEFAULT, PinIcon.fromUri(null))
    }

    @Test
    fun `pin icon URI validation rejects malformed values`() {
        assertTrue(PinIcon.isValidSystemIconUri("system://images/TIMELINE_CALENDAR"))
        assertFalse(PinIcon.isValidSystemIconUri("https://example.com/icon.png"))
        assertFalse(PinIcon.isValidSystemIconUri("system://images/"))
        assertFalse(PinIcon.isValidSystemIconUri("system://images/lowercase_bad"))
        assertFalse(PinIcon.isValidSystemIconUri("TIMELINE_CALENDAR"))
    }

    @Test
    fun `pin accent colors use valid Pebble 64-color hex codes and round-trip lookup`() {
        val pebbleChannels = setOf("00", "55", "AA", "FF")
        PinAccentColor.entries.forEach { color ->
            assertTrue(PinAccentColor.isValidHexColor(color.hexCode))
            assertTrue(color.hexCode.startsWith("#"))
            val raw = color.hexCode.removePrefix("#")
            assertTrue(raw.substring(0, 2) in pebbleChannels)
            assertTrue(raw.substring(2, 4) in pebbleChannels)
            assertTrue(raw.substring(4, 6) in pebbleChannels)
            assertEquals(color, PinAccentColor.fromHex(color.hexCode))
            assertEquals(color, PinAccentColor.fromHex(raw.lowercase()))
        }

        assertTrue(PinAccentColor.isValidHexColor("#FF4700"))
        assertTrue(PinAccentColor.isValidHexColor("FF4700"))
        assertFalse(PinAccentColor.isValidHexColor("#FFF"))
        assertFalse(PinAccentColor.isValidHexColor("orange"))
        assertNull(PinAccentColor.fromHex("#123456"))
    }

    @Test
    fun `duration parser handles instant events, valid bounds, and invalid input`() {
        assertEquals(DurationParseResult.None, PinDurationFormatter.parseDurationInput(""))
        assertEquals(DurationParseResult.None, PinDurationFormatter.parseDurationInput("   "))
        assertEquals(DurationParseResult.Valid(1), PinDurationFormatter.parseDurationInput("1"))
        assertEquals(DurationParseResult.Valid(45), PinDurationFormatter.parseDurationInput(" 45 "))
        assertEquals(DurationParseResult.Valid(1440), PinDurationFormatter.parseDurationInput("1440"))

        assertTrue(PinDurationFormatter.parseDurationInput("0") is DurationParseResult.Invalid)
        assertTrue(PinDurationFormatter.parseDurationInput("-15") is DurationParseResult.Invalid)
        assertTrue(PinDurationFormatter.parseDurationInput("1441") is DurationParseResult.Invalid)
        assertTrue(PinDurationFormatter.parseDurationInput("abc") is DurationParseResult.Invalid)
    }

    @Test
    fun `duration formatter produces readable labels`() {
        assertEquals("15 min", PinDurationFormatter.formatMinutes(15))
        assertEquals("1h", PinDurationFormatter.formatMinutes(60))
        assertEquals("1h 30m", PinDurationFormatter.formatMinutes(90))
        assertEquals("2h", PinDurationFormatter.formatMinutes(120))
    }
}
