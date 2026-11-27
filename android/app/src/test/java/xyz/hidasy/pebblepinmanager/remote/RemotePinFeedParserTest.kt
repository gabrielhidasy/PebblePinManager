package xyz.hidasy.pebblepinmanager.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.hidasy.pebblepinmanager.domain.EventSource
import xyz.hidasy.pebblepinmanager.domain.PinLayoutType
import xyz.hidasy.pebblepinmanager.domain.TimelineEvent
import java.time.Instant

class RemotePinFeedParserTest {
    private val nowMillis: Long = Instant.parse("2026-11-25T22:00:00Z").toEpochMilli()
    private val sourceKey: String = RemotePinSourceKey.forFeed("feed-test")

    @Test
    fun `parses valid schema v1 feed with ISO-8601 UTC, offset, and epoch millis timestamps`() {
        val futureEpoch = Instant.parse("2026-11-27T10:30:00Z").toEpochMilli()
        val payload = """
            {
              "schemaVersion": 1,
              "updatedAt": "2026-11-25T22:00:00Z",
              "pins": [
                {
                  "id": "ext-meeting-123",
                  "title": "Team Standup",
                  "description": "Daily sync in Room B",
                  "startTime": "2026-11-26T14:00:00Z",
                  "durationMinutes": 30,
                  "iconUri": "system://images/TIMELINE_CALENDAR",
                  "backgroundColor": "#00aaff",
                  "primaryColor": "FFFFFF",
                  "secondaryColor": "#cccccc",
                  "layoutType": "CALENDAR_PIN"
                },
                {
                  "id": "ext-offset-456",
                  "title": "Coffee Chat",
                  "startTime": "2026-11-26T11:00:00-03:00"
                },
                {
                  "id": "ext-epoch-789",
                  "title": "Release Window",
                  "startTimeMillis": $futureEpoch,
                  "layoutType": "calendar_pin"
                }
              ]
            }
        """.trimIndent()

        val result = RemotePinFeedParser.parse(payload, sourceKey, nowMillis)
        assertTrue(result is RemotePinFeedParseResult.Success)
        val success = result as RemotePinFeedParseResult.Success

        assertEquals(1, success.schemaVersion)
        assertEquals("2026-11-25T22:00:00Z", success.updatedAt)
        assertEquals(3, success.validEvents.size)
        assertEquals(0, success.diagnostics.size)
        assertEquals(setOf("ext-meeting-123", "ext-offset-456", "ext-epoch-789"), success.seenIds)

        val first = success.validEvents[0]
        assertEquals("ext-meeting-123", first.id)
        assertEquals("Team Standup", first.title)
        assertEquals("Daily sync in Room B", first.description)
        assertEquals(Instant.parse("2026-11-26T14:00:00Z").toEpochMilli(), first.startTimeMillis)
        assertEquals(30, first.durationMinutes)
        assertEquals("system://images/TIMELINE_CALENDAR", first.iconUri)
        assertEquals("#00AAFF", first.backgroundColor)
        assertEquals("#FFFFFF", first.primaryColor)
        assertEquals("#CCCCCC", first.secondaryColor)
        assertEquals(PinLayoutType.CALENDAR_PIN, first.layoutType)
        assertEquals(EventSource.REMOTE_URL, first.source)
        assertEquals(sourceKey, first.sourceKey)

        val second = success.validEvents[1]
        assertEquals(Instant.parse("2026-11-26T14:00:00Z").toEpochMilli(), second.startTimeMillis)
        assertEquals(TimelineEvent.DEFAULT_ICON_URI, second.iconUri)
        assertEquals(PinLayoutType.GENERIC_PIN, second.layoutType)

        val third = success.validEvents[2]
        assertEquals(futureEpoch, third.startTimeMillis)
        assertEquals(PinLayoutType.CALENDAR_PIN, third.layoutType)
    }

    @Test
    fun `skips invalid entries with diagnostics without aborting valid pins`() {
        val longTitle = "A".repeat(129)
        val longDesc = "B".repeat(513)
        val payload = """
            {
              "schemaVersion": 1,
              "pins": [
                {
                  "id": "valid-1",
                  "title": "Valid Pin",
                  "startTime": "2026-11-26T12:00:00Z",
                  "durationMinutes": 45
                },
                {
                  "id": "bad-title",
                  "title": "$longTitle",
                  "startTime": "2026-11-26T12:00:00Z"
                },
                {
                  "id": "bad-desc",
                  "title": "Too long description",
                  "description": "$longDesc",
                  "startTime": "2026-11-26T12:00:00Z"
                },
                {
                  "id": "bad-duration",
                  "title": "Bad duration",
                  "startTime": "2026-11-26T12:00:00Z",
                  "durationMinutes": 2000
                },
                {
                  "id": "bad-time",
                  "title": "Too old",
                  "startTime": "2020-01-01T00:00:00Z"
                },
                {
                  "id": "bad-layout",
                  "title": "Bad layout",
                  "startTime": "2026-11-26T12:00:00Z",
                  "layoutType": "UNKNOWN_LAYOUT"
                },
                {
                  "id": "bad-layout-reminder",
                  "title": "Unsupported reminder pin layout",
                  "startTime": "2026-11-26T12:00:00Z",
                  "layoutType": "GENERIC_REMINDER"
                },
                {
                  "id": "bad-layout-notification",
                  "title": "Unsupported notification pin layout",
                  "startTime": "2026-11-26T12:00:00Z",
                  "layoutType": "GENERIC_NOTIFICATION"
                },
                {
                  "id": "valid-1",
                  "title": "Duplicate ID should be skipped",
                  "startTime": "2026-11-26T13:00:00Z"
                },
                {
                  "title": "Missing ID",
                  "startTime": "2026-11-26T12:00:00Z"
                },
                {
                  "id": "valid-2",
                  "title": "Second Valid Pin",
                  "startTime": "2026-11-26T15:00:00Z"
                }
              ]
            }
        """.trimIndent()

        val result = RemotePinFeedParser.parse(payload, sourceKey, nowMillis)
        assertTrue(result is RemotePinFeedParseResult.Success)
        val success = result as RemotePinFeedParseResult.Success

        assertEquals(listOf("valid-1", "valid-2"), success.validEvents.map { it.id })
        assertEquals(9, success.diagnostics.size)
        // Seen IDs include entries that had a valid ID even when another field failed validation
        assertTrue("bad-duration" in success.seenIds)
        assertTrue("bad-title" in success.seenIds)
        assertTrue("bad-layout-reminder" in success.seenIds)
        assertTrue("bad-layout-notification" in success.seenIds)
        assertTrue(success.diagnostics.any { it.pinId == "bad-duration" && it.reason.contains("1440") })
        assertTrue(success.diagnostics.any { it.pinId == "bad-layout-reminder" && it.reason.contains("GENERIC_PIN, CALENDAR_PIN") })
        assertTrue(success.diagnostics.any { it.pinId == "bad-layout-notification" && it.reason.contains("GENERIC_PIN, CALENDAR_PIN") })
        assertTrue(success.diagnostics.any { it.pinId == "valid-1" && it.reason.contains("Duplicate") })
    }

    @Test
    fun `rejects unsupported schemaVersion and malformed root objects`() {
        val wrongVersion = """{"schemaVersion": 2, "pins": []}"""
        val res1 = RemotePinFeedParser.parse(wrongVersion, sourceKey, nowMillis)
        assertTrue(res1 is RemotePinFeedParseResult.InvalidFeed)
        assertTrue((res1 as RemotePinFeedParseResult.InvalidFeed).reason.contains("Unsupported feed schemaVersion 2"))

        val missingPins = """{"schemaVersion": 1, "status": "ok"}"""
        val res2 = RemotePinFeedParser.parse(missingPins, sourceKey, nowMillis)
        assertTrue(res2 is RemotePinFeedParseResult.InvalidFeed)
        assertTrue((res2 as RemotePinFeedParseResult.InvalidFeed).reason.contains("pins"))

        val badJson = """{not-valid-json"""
        val res3 = RemotePinFeedParser.parse(badJson, sourceKey, nowMillis)
        assertTrue(res3 is RemotePinFeedParseResult.InvalidFeed)
    }
}
