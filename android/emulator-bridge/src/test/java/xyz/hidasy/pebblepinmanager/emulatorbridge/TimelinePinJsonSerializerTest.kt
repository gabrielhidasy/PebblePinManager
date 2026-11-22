package xyz.hidasy.pebblepinmanager.emulatorbridge

import io.rebble.pebblekit2.common.model.TimelineLayout
import io.rebble.pebblekit2.common.model.TimelineLayoutType
import io.rebble.pebblekit2.common.model.TimelinePin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class TimelinePinJsonSerializerTest {

    @Test
    fun `isAuthorized accepts PebblePinManager debug and release packages for watchapp UUID`() {
        val uuid = TimelinePinJsonSerializer.AUTHORIZED_WATCHAPP_UUID
        assertTrue(TimelinePinJsonSerializer.isAuthorized("xyz.hidasy.pebblepinmanager", uuid))
        assertTrue(TimelinePinJsonSerializer.isAuthorized("xyz.hidasy.pebblepinmanager.debug", uuid))
        assertFalse(TimelinePinJsonSerializer.isAuthorized("com.example.untrusted", uuid))
        assertFalse(TimelinePinJsonSerializer.isAuthorized("xyz.hidasy.pebblepinmanager", UUID.randomUUID()))
    }

    @Test
    fun `serialize produces Pebble Timeline compatible JSON payload`() {
        val pin = TimelinePin(
            id = "ppm-e2e-1",
            startTime = Instant.fromEpochMilliseconds(1_795_366_800_000L),
            duration = 30.minutes,
            layout = TimelineLayout(
                type = TimelineLayoutType.GENERIC_PIN,
                title = "Demo Dentist Visit",
                body = "Suite 402 - bring card",
                tinyIcon = "system://images/NOTIFICATION_FLAG",
                backgroundColor = "#FF5500",
            ),
        )

        val json = TimelinePinJsonSerializer.serialize(
            callingPackage = "xyz.hidasy.pebblepinmanager.debug",
            watchappUuid = TimelinePinJsonSerializer.AUTHORIZED_WATCHAPP_UUID,
            pin = pin,
        )

        assertTrue(json.contains("\"id\":\"ppm-e2e-1\""))
        assertTrue(json.contains("\"duration\":30"))
        assertTrue(json.contains("\"type\":\"genericPin\""))
        assertTrue(json.contains("\"title\":\"Demo Dentist Visit\""))
        assertTrue(json.contains("\"body\":\"Suite 402 - bring card\""))
        assertTrue(json.contains("\"tinyIcon\":\"system://images/NOTIFICATION_FLAG\""))
        assertTrue(json.contains("\"backgroundColor\":\"#FF5500\""))
        assertTrue(json.contains("\"callingPackage\":\"xyz.hidasy.pebblepinmanager.debug\""))
    }

    @Test
    fun `forPlatform maps basalt and emery watch metadata`() {
        val basalt = BridgeWatchConfig.forPlatform("basalt")
        assertEquals("basalt", basalt.platform)
        assertEquals("Pebble Time Emulator", basalt.watchName)

        val emery = BridgeWatchConfig.forPlatform("emery")
        assertEquals("emery", emery.platform)
        assertEquals("Pebble Time 2 Emulator", emery.watchName)
    }
}
