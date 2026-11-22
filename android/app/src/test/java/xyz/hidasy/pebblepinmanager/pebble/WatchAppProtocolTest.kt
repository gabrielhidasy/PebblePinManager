package xyz.hidasy.pebblepinmanager.pebble

import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.hidasy.pebblepinmanager.data.SentPinRecord
import java.time.Instant
import java.time.ZoneId

class WatchAppProtocolTest {

    @Test
    fun `encodeSyncStart and encodePinItem populate expected keys and clamp lengths`() {
        val start = WatchAppProtocol.encodeSyncStart(count = 20, generation = 42)
        assertEquals(
            PebbleDictionaryItem.UInt8(WatchAppProtocol.MSG_PHONE_SYNC_START),
            start[WatchAppProtocol.KEY_MSG_TYPE],
        )
        assertEquals(
            PebbleDictionaryItem.UInt8(WatchAppProtocol.MAX_PINS),
            start[WatchAppProtocol.KEY_PIN_COUNT],
        )
        assertEquals(
            PebbleDictionaryItem.UInt16(42),
            start[WatchAppProtocol.KEY_SYNC_GENERATION],
        )

        val longTitle = "Very long timeline event title that exceeds forty bytes on Pebble watch"
        val item = WatchAppProtocol.encodePinItem(
            index = 0,
            totalCount = 3,
            generation = 42,
            summary = WatchPinSummary(
                id = "ppm-1234567890ab",
                title = longTitle,
                timeLabel = "Today 14:30 (30m)",
            ),
        )

        val encodedTitle = (item[WatchAppProtocol.KEY_PIN_TITLE] as PebbleDictionaryItem.Text).value
        assertTrue(encodedTitle.toByteArray(Charsets.UTF_8).size <= WatchAppProtocol.MAX_PIN_TITLE_BYTES)
        assertTrue(encodedTitle.endsWith("..."))
        assertEquals(
            PebbleDictionaryItem.Text("Today 14:30 (30m)"),
            item[WatchAppProtocol.KEY_PIN_TIME],
        )
    }

    @Test
    fun `truncateUtf8Bytes preserves multi-byte UTF-8 characters without splitting codepoints`() {
        val accented = "Café reunião manhã tarde noite"
        val truncated = WatchAppProtocol.truncateUtf8Bytes(accented, maxBytes = 15)
        assertTrue(truncated.toByteArray(Charsets.UTF_8).size <= 15)
        assertTrue(truncated.endsWith("..."))
        assertEquals(truncated, String(truncated.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
    }

    @Test
    fun `decodeWatchRequest handles sync, open phone, reschedule, delete, and invalid payloads`() {
        assertEquals(
            WatchRequest.SyncRequest,
            WatchAppProtocol.decodeWatchRequest(
                mapOf(WatchAppProtocol.KEY_MSG_TYPE to PebbleDictionaryItem.UInt8(WatchAppProtocol.MSG_WATCH_REQ_SYNC)),
            ),
        )
        assertEquals(
            WatchRequest.OpenPhoneUi,
            WatchAppProtocol.decodeWatchRequest(
                mapOf(WatchAppProtocol.KEY_MSG_TYPE to PebbleDictionaryItem.UInt8(WatchAppProtocol.MSG_WATCH_REQ_OPEN_PHONE)),
            ),
        )
        assertEquals(
            WatchRequest.ReschedulePin(pinId = "ppm-abc", deltaMinutes = 30, deltaDays = 0),
            WatchAppProtocol.decodeWatchRequest(
                mapOf(
                    WatchAppProtocol.KEY_MSG_TYPE to PebbleDictionaryItem.UInt8(WatchAppProtocol.MSG_WATCH_REQ_RESCHEDULE),
                    WatchAppProtocol.KEY_PIN_ID to PebbleDictionaryItem.Text("ppm-abc"),
                    WatchAppProtocol.KEY_DELTA_MINUTES to PebbleDictionaryItem.Int32(30),
                    WatchAppProtocol.KEY_DELTA_DAYS to PebbleDictionaryItem.Int32(0),
                ),
            ),
        )
        assertEquals(
            WatchRequest.ReschedulePin(pinId = "ppm-abc", deltaMinutes = 0, deltaDays = 1),
            WatchAppProtocol.decodeWatchRequest(
                mapOf(
                    WatchAppProtocol.KEY_MSG_TYPE to PebbleDictionaryItem.UInt8(WatchAppProtocol.MSG_WATCH_REQ_RESCHEDULE),
                    WatchAppProtocol.KEY_PIN_ID to PebbleDictionaryItem.Text("ppm-abc"),
                    WatchAppProtocol.KEY_DELTA_DAYS to PebbleDictionaryItem.Int32(1),
                ),
            ),
        )
        assertEquals(
            WatchRequest.DeletePin(pinId = "ppm-xyz"),
            WatchAppProtocol.decodeWatchRequest(
                mapOf(
                    WatchAppProtocol.KEY_MSG_TYPE to PebbleDictionaryItem.UInt8(WatchAppProtocol.MSG_WATCH_REQ_DELETE),
                    WatchAppProtocol.KEY_PIN_ID to PebbleDictionaryItem.Text(" ppm-xyz "),
                ),
            ),
        )

        // Zero-delta reschedule or blank pin ID should be rejected
        assertNull(
            WatchAppProtocol.decodeWatchRequest(
                mapOf(
                    WatchAppProtocol.KEY_MSG_TYPE to PebbleDictionaryItem.UInt8(WatchAppProtocol.MSG_WATCH_REQ_RESCHEDULE),
                    WatchAppProtocol.KEY_PIN_ID to PebbleDictionaryItem.Text("ppm-abc"),
                    WatchAppProtocol.KEY_DELTA_MINUTES to PebbleDictionaryItem.Int32(0),
                    WatchAppProtocol.KEY_DELTA_DAYS to PebbleDictionaryItem.Int32(0),
                ),
            ),
        )
        assertNull(
            WatchAppProtocol.decodeWatchRequest(
                mapOf(
                    WatchAppProtocol.KEY_MSG_TYPE to PebbleDictionaryItem.UInt8(WatchAppProtocol.MSG_WATCH_REQ_DELETE),
                    WatchAppProtocol.KEY_PIN_ID to PebbleDictionaryItem.Text("   "),
                ),
            ),
        )
        assertNull(WatchAppProtocol.decodeWatchRequest(emptyMap()))
    }

    @Test
    fun `WatchPinSummaryFormatter formats today, tomorrow, other dates, and optional duration`() {
        val zone = ZoneId.of("UTC")
        val nowMillis = Instant.parse("2026-11-22T10:00:00Z").toEpochMilli()

        val todayRecord = SentPinRecord(
            id = "ppm-1",
            title = "Standup",
            startTimeMillis = Instant.parse("2026-11-22T14:30:00Z").toEpochMilli(),
            durationMinutes = 30,
            sentAtMillis = nowMillis,
        )
        val tomorrowRecord = SentPinRecord(
            id = "ppm-2",
            title = "Dentist",
            startTimeMillis = Instant.parse("2026-11-23T09:15:00Z").toEpochMilli(),
            durationMinutes = null,
            sentAtMillis = nowMillis,
        )
        val futureRecord = SentPinRecord(
            id = "ppm-3",
            title = "Flight",
            startTimeMillis = Instant.parse("2026-12-05T18:45:00Z").toEpochMilli(),
            durationMinutes = 120,
            sentAtMillis = nowMillis,
        )

        assertEquals(
            "Today 14:30 (30m)",
            WatchPinSummaryFormatter.toWatchSummary(todayRecord, nowMillis, zone).timeLabel,
        )
        assertEquals(
            "Tmrw 09:15",
            WatchPinSummaryFormatter.toWatchSummary(tomorrowRecord, nowMillis, zone).timeLabel,
        )
        assertEquals(
            "Dec 5 18:45 (2h)",
            WatchPinSummaryFormatter.toWatchSummary(futureRecord, nowMillis, zone).timeLabel,
        )
    }
}
