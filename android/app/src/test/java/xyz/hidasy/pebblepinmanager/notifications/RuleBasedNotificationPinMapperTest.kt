package xyz.hidasy.pebblepinmanager.notifications

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.hidasy.pebblepinmanager.domain.EventSource
import xyz.hidasy.pebblepinmanager.domain.PinAccentColor
import xyz.hidasy.pebblepinmanager.domain.PinIcon
import xyz.hidasy.pebblepinmanager.domain.TimelineEvent
import java.time.Instant
import java.time.ZoneId

class RuleBasedNotificationPinMapperTest {
    private val fixedNow = Instant.parse("2026-11-22T10:00:00Z").toEpochMilli()
    private val utc = ZoneId.of("UTC")

    @Test
    fun `strictly enforces opt-in package allowlist and master toggle`() = runTest {
        val defaultMapper = RuleBasedNotificationPinMapper(
            settings = NotificationRuleSettings(), // empty allowlist by default
            clockMillis = { fixedNow },
            zoneIdProvider = { utc },
        )
        val candidate = NotificationPinCandidate(
            sourcePackage = "com.example.delivery",
            notificationKey = "0|com.example.delivery|1",
            title = "Package arriving",
            text = "Arriving at 14:00",
            eventTimeMillis = fixedNow,
        )
        assertNull(defaultMapper.mapOrNull(candidate, existingPinId = null))

        val disabledMapper = RuleBasedNotificationPinMapper(
            settings = NotificationRuleSettings(
                isAutoPinEnabled = false,
                allowedPackages = setOf("com.example.delivery"),
            ),
            clockMillis = { fixedNow },
            zoneIdProvider = { utc },
        )
        assertNull(disabledMapper.mapOrNull(candidate, existingPinId = null))

        val enabledMapper = RuleBasedNotificationPinMapper(
            settings = NotificationRuleSettings(
                isAutoPinEnabled = true,
                allowedPackages = setOf("com.example.delivery"),
            ),
            clockMillis = { fixedNow },
            zoneIdProvider = { utc },
            pinIdGenerator = { "ppm-notif-1" },
        )
        val mapped = enabledMapper.mapOrNull(candidate, existingPinId = null)
        assertNotNull(mapped)
        assertEquals("ppm-notif-1", mapped!!.id)
        assertEquals("Package arriving", mapped.title)
        assertEquals("Arriving at 14:00", mapped.description)
        assertEquals(Instant.parse("2026-11-22T14:00:00Z").toEpochMilli(), mapped.startTimeMillis)
        assertEquals(EventSource.NOTIFICATION, mapped.source)
        assertEquals("0|com.example.delivery|1", mapped.sourceKey)
    }

    @Test
    fun `filters out ongoing and group summary notifications by default`() = runTest {
        val mapper = RuleBasedNotificationPinMapper(
            settings = NotificationRuleSettings(
                allowedPackages = setOf("com.example.chat"),
                ignoreOngoing = true,
                ignoreGroupSummary = true,
            ),
            clockMillis = { fixedNow },
            zoneIdProvider = { utc },
        )

        val ongoing = NotificationPinCandidate(
            sourcePackage = "com.example.chat",
            notificationKey = "0|com.example.chat|1",
            title = "Active call",
            text = "00:12",
            eventTimeMillis = fixedNow,
            isOngoing = true,
        )
        val summary = NotificationPinCandidate(
            sourcePackage = "com.example.chat",
            notificationKey = "0|com.example.chat|2",
            title = "3 new messages",
            text = "Alice, Bob",
            eventTimeMillis = fixedNow,
            isGroupSummary = true,
        )

        assertNull(mapper.mapOrNull(ongoing, null))
        assertNull(mapper.mapOrNull(summary, null))
    }

    @Test
    fun `applies case-insensitive keyword filter across title and body`() = runTest {
        val mapper = RuleBasedNotificationPinMapper(
            settings = NotificationRuleSettings(
                allowedPackages = setOf("com.example.mail"),
                keywordFilter = "flight, delivery, gate",
                extractTimeFromText = false,
            ),
            clockMillis = { fixedNow },
            zoneIdProvider = { utc },
            pinIdGenerator = { "ppm-notif-kw" },
        )

        val nonMatching = NotificationPinCandidate(
            sourcePackage = "com.example.mail",
            notificationKey = "0|com.example.mail|10",
            title = "Weekly newsletter",
            text = "Top 5 productivity tips",
            eventTimeMillis = fixedNow + 60_000L,
        )
        assertNull(mapper.mapOrNull(nonMatching, null))

        val matching = NotificationPinCandidate(
            sourcePackage = "com.example.mail",
            notificationKey = "0|com.example.mail|11",
            title = "Carrier update",
            text = "Out for DELIVERY today",
            eventTimeMillis = fixedNow + 60_000L,
        )
        val event = mapper.mapOrNull(matching, null)
        assertNotNull(event)
        assertEquals("Carrier update", event!!.title)
    }

    @Test
    fun `extracts title, description, and time with custom regexes and falls back safely`() = runTest {
        val mapper = RuleBasedNotificationPinMapper(
            settings = NotificationRuleSettings(
                allowedPackages = setOf("com.example.airline"),
                titleRegexPattern = """Flight\s+([A-Z0-9]+)""",
                descriptionRegexPattern = """(Gate\s+[A-Z0-9]+,\s*Seat\s+[A-Z0-9]+)""",
                timeRegexPattern = """Boards\s+(\d{1,2}:\d{2})""",
                defaultIconUri = PinIcon.SCHEDULED_FLIGHT.uri,
                defaultBackgroundColor = PinAccentColor.SKY.hexCode,
                defaultDurationMinutes = 45,
            ),
            clockMillis = { fixedNow },
            zoneIdProvider = { utc },
            pinIdGenerator = { "ppm-notif-flight" },
        )

        val candidate = NotificationPinCandidate(
            sourcePackage = "com.example.airline",
            notificationKey = "0|com.example.airline|99",
            title = "Alert: Flight LH456 Update",
            text = "Short summary",
            bigText = "Updated 10:05. Gate B12, Seat 14A. Boards 16:30",
            eventTimeMillis = fixedNow,
        )

        val event = mapper.mapOrNull(candidate, existingPinId = "ppm-notif-existing")
        assertNotNull(event)
        assertEquals("ppm-notif-existing", event!!.id)
        assertEquals("LH456", event.title)
        assertEquals("Gate B12, Seat 14A", event.description)
        assertEquals(Instant.parse("2026-11-22T16:30:00Z").toEpochMilli(), event.startTimeMillis)
        assertEquals(PinIcon.SCHEDULED_FLIGHT.uri, event.iconUri)
        assertEquals(PinAccentColor.SKY.hexCode, event.backgroundColor)
        assertEquals(45, event.durationMinutes)
    }

    @Test
    fun `truncates oversized notification titles and bigText to Pebble Timeline limits`() = runTest {
        val mapper = RuleBasedNotificationPinMapper(
            settings = NotificationRuleSettings(
                allowedPackages = setOf("com.example.notes"),
                extractTimeFromText = false,
            ),
            clockMillis = { fixedNow },
            zoneIdProvider = { utc },
        )

        val longTitle = "A".repeat(200)
        val longBody = "B".repeat(700)
        val event = mapper.mapOrNull(
            NotificationPinCandidate(
                sourcePackage = "com.example.notes",
                notificationKey = "0|com.example.notes|1",
                title = longTitle,
                text = "short",
                bigText = longBody,
                eventTimeMillis = fixedNow,
            ),
            existingPinId = null,
        )

        assertNotNull(event)
        assertEquals(TimelineEvent.MAX_TITLE_LENGTH, event!!.title.length)
        assertTrue(event.title.endsWith("…"))
        assertEquals(TimelineEvent.MAX_DESCRIPTION_LENGTH, event.description!!.length)
        assertTrue(event.description!!.endsWith("…"))
    }

    @Test
    fun `ignores invalid regex syntax without crashing and falls back to raw fields`() = runTest {
        val mapper = RuleBasedNotificationPinMapper(
            settings = NotificationRuleSettings(
                allowedPackages = setOf("com.example.app"),
                titleRegexPattern = "[broken",
                descriptionRegexPattern = "(unclosed",
                timeRegexPattern = "*invalid",
            ),
            clockMillis = { fixedNow },
            zoneIdProvider = { utc },
        )

        val event = mapper.mapOrNull(
            NotificationPinCandidate(
                sourcePackage = "com.example.app",
                notificationKey = "0|com.example.app|5",
                title = "Package Delivered",
                text = "Front porch",
                eventTimeMillis = fixedNow,
            ),
            existingPinId = null,
        )

        assertNotNull(event)
        assertEquals("Package Delivered", event!!.title)
        assertEquals("Front porch", event.description)
        assertEquals(fixedNow, event.startTimeMillis)
    }
}
