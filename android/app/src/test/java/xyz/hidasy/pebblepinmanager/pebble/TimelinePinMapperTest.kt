package xyz.hidasy.pebblepinmanager.pebble

import io.rebble.pebblekit2.common.model.TimelineLayoutType
import io.rebble.pebblekit2.common.model.TimelineResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import io.rebble.pebblekit2.model.ConnectedWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.hidasy.pebblepinmanager.data.SentPinRecord
import xyz.hidasy.pebblepinmanager.domain.PinAccentColor
import xyz.hidasy.pebblepinmanager.domain.PinIcon
import xyz.hidasy.pebblepinmanager.domain.PinLayoutType
import xyz.hidasy.pebblepinmanager.domain.TimelineEvent
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

class TimelinePinMapperTest {
    private val testUuid = UUID.fromString("e4b7c3a1-8f2d-4c6e-9a51-7b3d2e8f1042")

    @Test
    fun `maps domain TimelineEvent to PebbleKit2 TimelinePin accurately`() {
        val startMillis = Instant.parse("2026-11-21T19:30:00Z").toEpochMilli()
        val event = TimelineEvent(
            id = "ppm-abc123",
            title = "  Dentist  ",
            description = "  Room 402  ",
            startTimeMillis = startMillis,
            durationMinutes = 45,
            iconUri = "system://images/TIMELINE_CALENDAR",
            backgroundColor = "FF4700",
            layoutType = PinLayoutType.CALENDAR_PIN,
        )

        val pin = TimelinePinMapper.toPebbleKitPin(event)

        assertEquals("ppm-abc123", pin.id)
        assertEquals(startMillis, pin.startTime.toEpochMilliseconds())
        assertEquals(45.minutes, pin.duration)
        assertEquals(TimelineLayoutType.CALENDAR_PIN, pin.layout.type)
        assertEquals("Dentist", pin.layout.title)
        assertEquals("Room 402", pin.layout.body)
        assertEquals("system://images/TIMELINE_CALENDAR", pin.layout.tinyIcon)
        assertEquals("FF4700", pin.layout.backgroundColor)
    }

    @Test
    fun `maps all PebbleKit2 TimelineResult variants for insert`() {
        assertEquals(
            PinSendResult.Success("pin-1"),
            TimelinePinMapper.toPinSendResult("pin-1", testUuid, TimelineResult.Success),
        )
        assertEquals(
            PinSendResult.NoPebbleApp,
            TimelinePinMapper.toPinSendResult("pin-1", testUuid, TimelineResult.FailedNoPebbleApp),
        )
        assertEquals(
            PinSendResult.NoPermissions(testUuid.toString()),
            TimelinePinMapper.toPinSendResult("pin-1", testUuid, TimelineResult.FailedNoPermissions),
        )
        assertEquals(
            PinSendResult.UnsupportedAction,
            TimelinePinMapper.toPinSendResult("pin-1", testUuid, TimelineResult.FailedUnsupportedAction),
        )
        assertTrue(
            TimelinePinMapper.toPinSendResult(
                "pin-1",
                testUuid,
                TimelineResult.Unknown("IPC timeout"),
            ) is PinSendResult.UnexpectedError,
        )
    }

    @Test
    fun `maps all PebbleKit2 TimelineResult variants for delete`() {
        assertEquals(
            PinDeleteResult.Success("pin-1"),
            TimelinePinMapper.toPinDeleteResult("pin-1", testUuid, TimelineResult.Success),
        )
        assertEquals(
            PinDeleteResult.UnknownPin("pin-1"),
            TimelinePinMapper.toPinDeleteResult("pin-1", testUuid, TimelineResult.FailedUnknownPin),
        )
        assertEquals(
            PinDeleteResult.NoPebbleApp,
            TimelinePinMapper.toPinDeleteResult("pin-1", testUuid, TimelineResult.FailedNoPebbleApp),
        )
        assertEquals(
            PinDeleteResult.NoPermissions(testUuid.toString()),
            TimelinePinMapper.toPinDeleteResult("pin-1", testUuid, TimelineResult.FailedNoPermissions),
        )
        assertEquals(
            PinDeleteResult.UnsupportedAction,
            TimelinePinMapper.toPinDeleteResult("pin-1", testUuid, TimelineResult.FailedUnsupportedAction),
        )
        assertTrue(
            TimelinePinMapper.toPinDeleteResult(
                "pin-1",
                testUuid,
                TimelineResult.Unknown("Delete IPC timeout"),
            ) is PinDeleteResult.UnexpectedError,
        )
    }

    @Test
    fun `recognizes Pebble Time basalt and Pebble Time 2 emery hardware platforms`() {
        val basalt = TimelinePinMapper.toConnectedPebbleWatch(
            ConnectedWatch(
                id = WatchIdentifier("watch-basalt"),
                name = "Pebble Time 1234",
                platform = "basalt",
                revision = "snowy_dvt",
                firmwareVersionMajor = 4,
                firmwareVersionMinor = 3,
                firmwareVersionPatch = 0,
                firmwareVersionTag = null,
            ),
        )
        val emery = TimelinePinMapper.toConnectedPebbleWatch(
            ConnectedWatch(
                id = WatchIdentifier("watch-emery"),
                name = "Pebble Time 2",
                platform = "emery",
                revision = "robert_evt",
                firmwareVersionMajor = 4,
                firmwareVersionMinor = 9,
                firmwareVersionPatch = 2,
                firmwareVersionTag = "core",
            ),
        )

        assertEquals(PebbleHardwarePlatform.BASALT, basalt.platform)
        assertTrue(basalt.platform.isTargetColorTimelineHardware)
        assertEquals("4.3.0", basalt.firmwareVersion)
        assertEquals("Pebble Time 1234 — Pebble Time (basalt)", basalt.displayLabel)

        assertEquals(PebbleHardwarePlatform.EMERY, emery.platform)
        assertTrue(emery.platform.isTargetColorTimelineHardware)
        assertEquals("4.9.2-core", emery.firmwareVersion)
        assertEquals("Pebble Time 2 — Pebble Time 2 (emery)", emery.displayLabel)
    }

    @Test
    fun `distinguishes color-screen platforms from monochrome platforms`() {
        assertTrue(PebbleHardwarePlatform.BASALT.supportsColorTimeline)
        assertTrue(PebbleHardwarePlatform.CHALK.supportsColorTimeline)
        assertTrue(PebbleHardwarePlatform.EMERY.supportsColorTimeline)
        assertTrue(PebbleHardwarePlatform.GABBRO.supportsColorTimeline)

        assertFalse(PebbleHardwarePlatform.APLITE.supportsColorTimeline)
        assertFalse(PebbleHardwarePlatform.DIORITE.supportsColorTimeline)
        assertFalse(PebbleHardwarePlatform.FLINT.supportsColorTimeline)
        assertFalse(PebbleHardwarePlatform.UNKNOWN.supportsColorTimeline)
    }

    @Test
    fun `maps instant event with custom icon and null duration or accent color`() {
        val startMillis = Instant.parse("2026-11-21T19:30:00Z").toEpochMilli()
        val instantPin = TimelinePinMapper.toPebbleKitPin(
            TimelineEvent(
                id = "ppm-instant",
                title = "Podcast Drop",
                startTimeMillis = startMillis,
                durationMinutes = null,
                iconUri = PinIcon.AUDIO_CASSETTE.uri,
                backgroundColor = null,
            ),
        )

        assertNull(instantPin.duration)
        assertEquals("system://images/AUDIO_CASSETTE", instantPin.layout.tinyIcon)
        assertNull(instantPin.layout.backgroundColor)

        val coloredPin = TimelinePinMapper.toPebbleKitPin(
            TimelineEvent(
                id = "ppm-colored",
                title = "Rain Alert",
                startTimeMillis = startMillis,
                durationMinutes = 60,
                iconUri = PinIcon.TIMELINE_WEATHER.uri,
                backgroundColor = PinAccentColor.SKY.hexCode,
            ),
        )

        assertEquals(60.minutes, coloredPin.duration)
        assertEquals("system://images/TIMELINE_WEATHER", coloredPin.layout.tinyIcon)
        assertEquals("#00AAFF", coloredPin.layout.backgroundColor)
    }

    @Test
    fun `legacy stored reminder or notification layout falls back safely to GENERIC_PIN`() {
        val startMillis = Instant.parse("2026-11-21T19:30:00Z").toEpochMilli()
        for (legacyLayout in listOf("GENERIC_REMINDER", "GENERIC_NOTIFICATION")) {
            val record = SentPinRecord(
                id = "ppm-legacy",
                title = "Legacy Pin",
                startTimeMillis = startMillis,
                layoutType = legacyLayout,
                sentAtMillis = startMillis,
            )
            val event = record.toTimelineEvent()
            assertEquals(PinLayoutType.GENERIC_PIN, event.layoutType)

            val pin = TimelinePinMapper.toPebbleKitPin(event)
            assertEquals(TimelineLayoutType.GENERIC_PIN, pin.layout.type)
        }
    }
}
