package xyz.hidasy.pebblepinmanager.pebble

import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.hidasy.pebblepinmanager.data.SentPinRecord
import xyz.hidasy.pebblepinmanager.data.SentPinRepository
import xyz.hidasy.pebblepinmanager.domain.EventSource
import xyz.hidasy.pebblepinmanager.domain.TimelineEvent
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class WatchPinSyncManagerTest {
    private val watch = WatchIdentifier("watch-1")
    private val fixedZone = ZoneId.of("UTC")
    private val fixedClock = Clock.fixed(Instant.parse("2026-11-22T12:00:00Z"), fixedZone)

    @Test
    fun `syncRecentPins transmits start packet followed by capped pin summaries`() = runTest {
        val repo = InMemorySentPinRepository()
        repeat(15) { i ->
            repo.saveSentPin(
                SentPinRecord(
                    id = "ppm-$i",
                    title = "Pin $i",
                    startTimeMillis = Instant.parse("2026-11-22T15:00:00Z").plusSeconds(i * 60L).toEpochMilli(),
                    sentAtMillis = 1_000L + i,
                ),
            )
        }
        val messenger = RecordingWatchAppMessenger()
        val manager = WatchPinSyncManager(
            pinSender = FakeTimelinePinSender(),
            repository = repo,
            messenger = messenger,
            onOpenPhoneUi = { PhoneUiOpenResult.OpenedDirect(DirectLaunchReason.APP_IN_FOREGROUND) },
            clock = fixedClock,
        )

        val result = manager.syncRecentPins(targetWatch = watch)

        assertEquals(WatchSyncResult.Synced(WatchAppProtocol.MAX_PINS), result)
        assertEquals(1 + WatchAppProtocol.MAX_PINS, messenger.sentMessages.size)
        val start = messenger.sentMessages.first().data
        assertEquals(
            PebbleDictionaryItem.UInt8(WatchAppProtocol.MSG_PHONE_SYNC_START),
            start[WatchAppProtocol.KEY_MSG_TYPE],
        )
        assertEquals(
            PebbleDictionaryItem.UInt8(WatchAppProtocol.MAX_PINS),
            start[WatchAppProtocol.KEY_PIN_COUNT],
        )
        // Newest pin (sentAtMillis largest -> ppm-14) should be index 0
        val firstPin = messenger.sentMessages[1].data
        assertEquals(PebbleDictionaryItem.Text("ppm-14"), firstPin[WatchAppProtocol.KEY_PIN_ID])
        assertEquals(PebbleDictionaryItem.UInt8(0), firstPin[WatchAppProtocol.KEY_PIN_INDEX])
    }

    @Test
    fun `syncRecentPins aborts cleanly when watchapp is not open`() = runTest {
        val repo = InMemorySentPinRepository(
            initial = listOf(
                SentPinRecord(
                    id = "ppm-1",
                    title = "Meeting",
                    startTimeMillis = Instant.parse("2026-11-22T14:00:00Z").toEpochMilli(),
                    sentAtMillis = 1000L,
                ),
            ),
        )
        val messenger = RecordingWatchAppMessenger(nextResult = WatchMessageResult.AppNotOpen)
        val manager = WatchPinSyncManager(
            pinSender = FakeTimelinePinSender(),
            repository = repo,
            messenger = messenger,
            onOpenPhoneUi = { PhoneUiOpenResult.OpenedDirect(DirectLaunchReason.APP_IN_FOREGROUND) },
            clock = fixedClock,
        )

        val result = manager.syncRecentPins()

        assertEquals(WatchSyncResult.Skipped(WatchMessageResult.AppNotOpen), result)
        assertEquals(1, messenger.sentMessages.size)
    }

    @Test
    fun `handleWatchMessage reschedules pin in-place, persists update, and re-syncs watch`() = runTest {
        val originalStart = Instant.parse("2026-11-22T14:00:00Z").toEpochMilli()
        val repo = InMemorySentPinRepository(
            initial = listOf(
                SentPinRecord(
                    id = "ppm-resched",
                    title = "Dentist",
                    description = "Room 4",
                    startTimeMillis = originalStart,
                    durationMinutes = 45,
                    backgroundColor = "#FF5500",
                    sentAtMillis = 5000L,
                ),
            ),
        )
        val pinSender = FakeTimelinePinSender()
        val messenger = RecordingWatchAppMessenger()
        val manager = WatchPinSyncManager(
            pinSender = pinSender,
            repository = repo,
            messenger = messenger,
            onOpenPhoneUi = { PhoneUiOpenResult.OpenedDirect(DirectLaunchReason.APP_IN_FOREGROUND) },
            clock = fixedClock,
        )

        val request = mapOf(
            WatchAppProtocol.KEY_MSG_TYPE to PebbleDictionaryItem.UInt8(WatchAppProtocol.MSG_WATCH_REQ_RESCHEDULE),
            WatchAppProtocol.KEY_PIN_ID to PebbleDictionaryItem.Text("ppm-resched"),
            WatchAppProtocol.KEY_DELTA_MINUTES to PebbleDictionaryItem.Int32(30),
            WatchAppProtocol.KEY_DELTA_DAYS to PebbleDictionaryItem.Int32(0),
        )

        val outcome = manager.handleWatchMessage(request, watch)

        val expectedNewStart = Instant.parse("2026-11-22T14:30:00Z").toEpochMilli()
        assertEquals(WatchHandleOutcome.PinRescheduled("ppm-resched", expectedNewStart), outcome)
        assertEquals(1, pinSender.sentEvents.size)
        assertEquals("ppm-resched", pinSender.sentEvents.single().id)
        assertEquals(expectedNewStart, pinSender.sentEvents.single().startTimeMillis)
        assertEquals("#FF5500", pinSender.sentEvents.single().backgroundColor)
        assertEquals(45, pinSender.sentEvents.single().durationMinutes)

        val persisted = repo.getPinById("ppm-resched")!!
        assertEquals(expectedNewStart, persisted.startTimeMillis)
        assertEquals(5000L, persisted.sentAtMillis)

        // Action result + sync start + 1 pin item
        assertEquals(3, messenger.sentMessages.size)
        val actionResult = messenger.sentMessages[0].data
        assertEquals(
            PebbleDictionaryItem.UInt8(WatchAppProtocol.MSG_PHONE_ACTION_RESULT),
            actionResult[WatchAppProtocol.KEY_MSG_TYPE],
        )
        assertEquals(PebbleDictionaryItem.UInt8(1), actionResult[WatchAppProtocol.KEY_STATUS_OK])
        assertEquals(
            PebbleDictionaryItem.Text("Moved: Today 14:30"),
            actionResult[WatchAppProtocol.KEY_STATUS_TEXT],
        )
        val syncedPin = messenger.sentMessages[2].data
        assertEquals(PebbleDictionaryItem.Text("Today 14:30 (45m)"), syncedPin[WatchAppProtocol.KEY_PIN_TIME])
    }

    @Test
    fun `reschedule by +1 day preserves wall-clock hour across DST transition`() = runTest {
        val nyZone = ZoneId.of("America/New_York")
        // 2026-11-01 is fall-back DST day in America/New_York (EDT UTC-4 -> EST UTC-5)
        val dstClock = Clock.fixed(Instant.parse("2026-10-31T14:00:00Z"), nyZone)
        val oct31At0900 = Instant.parse("2026-10-31T13:00:00Z").toEpochMilli() // 09:00 EDT
        val repo = InMemorySentPinRepository(
            initial = listOf(
                SentPinRecord(
                    id = "ppm-dst",
                    title = "Morning alarm",
                    startTimeMillis = oct31At0900,
                    sentAtMillis = 1000L,
                ),
            ),
        )
        val pinSender = FakeTimelinePinSender()
        val manager = WatchPinSyncManager(
            pinSender = pinSender,
            repository = repo,
            messenger = RecordingWatchAppMessenger(),
            onOpenPhoneUi = { PhoneUiOpenResult.OpenedDirect(DirectLaunchReason.APP_IN_FOREGROUND) },
            clock = dstClock,
            zoneIdProvider = { nyZone },
        )

        val outcome = manager.handleWatchMessage(
            data = mapOf(
                WatchAppProtocol.KEY_MSG_TYPE to PebbleDictionaryItem.UInt8(WatchAppProtocol.MSG_WATCH_REQ_RESCHEDULE),
                WatchAppProtocol.KEY_PIN_ID to PebbleDictionaryItem.Text("ppm-dst"),
                WatchAppProtocol.KEY_DELTA_MINUTES to PebbleDictionaryItem.Int32(0),
                WatchAppProtocol.KEY_DELTA_DAYS to PebbleDictionaryItem.Int32(1),
            ),
            watch = watch,
        )

        // Nov 1 09:00 EST is 14:00Z (25 hours after Oct 31 09:00 EDT)
        val expectedNov1At0900 = Instant.parse("2026-11-01T14:00:00Z").toEpochMilli()
        assertEquals(WatchHandleOutcome.PinRescheduled("ppm-dst", expectedNov1At0900), outcome)
    }

    @Test
    fun `reschedule rejects timestamps outside Pebble Timeline window`() = runTest {
        val nearPastLimit = Instant.parse("2026-11-20T12:10:00Z").toEpochMilli()
        val repo = InMemorySentPinRepository(
            initial = listOf(
                SentPinRecord(
                    id = "ppm-old",
                    title = "Old event",
                    startTimeMillis = nearPastLimit,
                    sentAtMillis = 1000L,
                ),
            ),
        )
        val pinSender = FakeTimelinePinSender()
        val messenger = RecordingWatchAppMessenger()
        val manager = WatchPinSyncManager(
            pinSender = pinSender,
            repository = repo,
            messenger = messenger,
            onOpenPhoneUi = { PhoneUiOpenResult.OpenedDirect(DirectLaunchReason.APP_IN_FOREGROUND) },
            clock = fixedClock,
        )

        val outcome = manager.handleWatchMessage(
            data = mapOf(
                WatchAppProtocol.KEY_MSG_TYPE to PebbleDictionaryItem.UInt8(WatchAppProtocol.MSG_WATCH_REQ_RESCHEDULE),
                WatchAppProtocol.KEY_PIN_ID to PebbleDictionaryItem.Text("ppm-old"),
                WatchAppProtocol.KEY_DELTA_MINUTES to PebbleDictionaryItem.Int32(-60),
                WatchAppProtocol.KEY_DELTA_DAYS to PebbleDictionaryItem.Int32(0),
            ),
            watch = watch,
        )

        assertTrue(outcome is WatchHandleOutcome.ActionFailed)
        assertTrue(pinSender.sentEvents.isEmpty())
        val feedback = messenger.sentMessages.single().data
        assertEquals(PebbleDictionaryItem.UInt8(0), feedback[WatchAppProtocol.KEY_STATUS_OK])
        assertEquals(PebbleDictionaryItem.Text("Time out of range"), feedback[WatchAppProtocol.KEY_STATUS_TEXT])
    }

    @Test
    fun `handleWatchMessage deletes pin on watch and locally then syncs updated empty list`() = runTest {
        val repo = InMemorySentPinRepository(
            initial = listOf(
                SentPinRecord(
                    id = "ppm-del",
                    title = "Remove me",
                    startTimeMillis = Instant.parse("2026-11-22T15:00:00Z").toEpochMilli(),
                    sentAtMillis = 2000L,
                ),
            ),
        )
        val pinSender = FakeTimelinePinSender()
        val messenger = RecordingWatchAppMessenger()
        val manager = WatchPinSyncManager(
            pinSender = pinSender,
            repository = repo,
            messenger = messenger,
            onOpenPhoneUi = { PhoneUiOpenResult.OpenedDirect(DirectLaunchReason.APP_IN_FOREGROUND) },
            clock = fixedClock,
        )

        val outcome = manager.handleWatchMessage(
            data = mapOf(
                WatchAppProtocol.KEY_MSG_TYPE to PebbleDictionaryItem.UInt8(WatchAppProtocol.MSG_WATCH_REQ_DELETE),
                WatchAppProtocol.KEY_PIN_ID to PebbleDictionaryItem.Text("ppm-del"),
            ),
            watch = watch,
        )

        assertEquals(WatchHandleOutcome.PinDeleted("ppm-del"), outcome)
        assertEquals(listOf("ppm-del"), pinSender.deletedIds)
        assertNull(repo.getPinById("ppm-del"))

        // Action result ("Pin deleted") + sync start with count = 0
        assertEquals(2, messenger.sentMessages.size)
        assertEquals(
            PebbleDictionaryItem.Text("Pin deleted"),
            messenger.sentMessages[0].data[WatchAppProtocol.KEY_STATUS_TEXT],
        )
        assertEquals(
            PebbleDictionaryItem.UInt8(0),
            messenger.sentMessages[1].data[WatchAppProtocol.KEY_PIN_COUNT],
        )
    }

    @Test
    fun `delete keeps local record intact and reports error when Pebble companion denies permission`() = runTest {
        val repo = InMemorySentPinRepository(
            initial = listOf(
                SentPinRecord(
                    id = "ppm-keep",
                    title = "Keep me",
                    startTimeMillis = Instant.parse("2026-11-22T15:00:00Z").toEpochMilli(),
                    sentAtMillis = 2000L,
                ),
            ),
        )
        val pinSender = FakeTimelinePinSender(
            deleteResult = PinDeleteResult.NoPermissions(PebblePinConfig.DEFAULT_WATCHAPP_UUID.toString()),
        )
        val messenger = RecordingWatchAppMessenger()
        val manager = WatchPinSyncManager(
            pinSender = pinSender,
            repository = repo,
            messenger = messenger,
            onOpenPhoneUi = { PhoneUiOpenResult.OpenedDirect(DirectLaunchReason.APP_IN_FOREGROUND) },
            clock = fixedClock,
        )

        val outcome = manager.handleWatchMessage(
            data = mapOf(
                WatchAppProtocol.KEY_MSG_TYPE to PebbleDictionaryItem.UInt8(WatchAppProtocol.MSG_WATCH_REQ_DELETE),
                WatchAppProtocol.KEY_PIN_ID to PebbleDictionaryItem.Text("ppm-keep"),
            ),
            watch = watch,
        )

        assertEquals(WatchHandleOutcome.ActionFailed("No permission"), outcome)
        assertEquals("ppm-keep", repo.getPinById("ppm-keep")?.id)
        val feedback = messenger.sentMessages.single().data
        assertEquals(PebbleDictionaryItem.UInt8(0), feedback[WatchAppProtocol.KEY_STATUS_OK])
        assertEquals(PebbleDictionaryItem.Text("No permission"), feedback[WatchAppProtocol.KEY_STATUS_TEXT])
    }

    @Test
    fun `handleWatchMessage open phone request reports direct foreground open`() = runTest {
        var openCount = 0
        val messenger = RecordingWatchAppMessenger()
        val manager = WatchPinSyncManager(
            pinSender = FakeTimelinePinSender(),
            repository = InMemorySentPinRepository(),
            messenger = messenger,
            onOpenPhoneUi = {
                openCount++
                PhoneUiOpenResult.OpenedDirect(DirectLaunchReason.APP_IN_FOREGROUND)
            },
            clock = fixedClock,
        )

        val outcome = manager.handleWatchMessage(
            data = mapOf(
                WatchAppProtocol.KEY_MSG_TYPE to PebbleDictionaryItem.UInt8(WatchAppProtocol.MSG_WATCH_REQ_OPEN_PHONE),
            ),
            watch = watch,
        )

        assertEquals(
            WatchHandleOutcome.OpenPhoneHandled(
                PhoneUiOpenResult.OpenedDirect(DirectLaunchReason.APP_IN_FOREGROUND),
            ),
            outcome,
        )
        assertEquals(1, openCount)
        val feedback = messenger.sentMessages.single().data
        assertEquals(PebbleDictionaryItem.UInt8(1), feedback[WatchAppProtocol.KEY_STATUS_OK])
        assertEquals(
            PebbleDictionaryItem.Text("Opened phone app"),
            feedback[WatchAppProtocol.KEY_STATUS_TEXT],
        )
    }

    @Test
    fun `handleWatchMessage open phone request reports background notification handoff`() = runTest {
        val messenger = RecordingWatchAppMessenger()
        val manager = WatchPinSyncManager(
            pinSender = FakeTimelinePinSender(),
            repository = InMemorySentPinRepository(),
            messenger = messenger,
            onOpenPhoneUi = { PhoneUiOpenResult.NotificationPosted },
            clock = fixedClock,
        )

        val outcome = manager.handleWatchMessage(
            data = mapOf(
                WatchAppProtocol.KEY_MSG_TYPE to PebbleDictionaryItem.UInt8(WatchAppProtocol.MSG_WATCH_REQ_OPEN_PHONE),
            ),
            watch = watch,
        )

        assertEquals(
            WatchHandleOutcome.OpenPhoneHandled(PhoneUiOpenResult.NotificationPosted),
            outcome,
        )
        val feedback = messenger.sentMessages.single().data
        assertEquals(PebbleDictionaryItem.UInt8(1), feedback[WatchAppProtocol.KEY_STATUS_OK])
        assertEquals(
            PebbleDictionaryItem.Text("Tap phone notification"),
            feedback[WatchAppProtocol.KEY_STATUS_TEXT],
        )
    }

    @Test
    fun `handleWatchMessage open phone request reports error when blocked in background`() = runTest {
        val messenger = RecordingWatchAppMessenger()
        val manager = WatchPinSyncManager(
            pinSender = FakeTimelinePinSender(),
            repository = InMemorySentPinRepository(),
            messenger = messenger,
            onOpenPhoneUi = { PhoneUiOpenResult.Blocked("BAL blocked and notifications disabled") },
            clock = fixedClock,
        )

        val outcome = manager.handleWatchMessage(
            data = mapOf(
                WatchAppProtocol.KEY_MSG_TYPE to PebbleDictionaryItem.UInt8(WatchAppProtocol.MSG_WATCH_REQ_OPEN_PHONE),
            ),
            watch = watch,
        )

        assertTrue(outcome is WatchHandleOutcome.OpenPhoneHandled)
        assertTrue((outcome as WatchHandleOutcome.OpenPhoneHandled).result is PhoneUiOpenResult.Blocked)
        val feedback = messenger.sentMessages.single().data
        assertEquals(PebbleDictionaryItem.UInt8(0), feedback[WatchAppProtocol.KEY_STATUS_OK])
        assertEquals(
            PebbleDictionaryItem.Text("Open app on phone"),
            feedback[WatchAppProtocol.KEY_STATUS_TEXT],
        )
    }

    @Test
    fun `handleWatchMessage open phone request handles launcher exception gracefully`() = runTest {
        val messenger = RecordingWatchAppMessenger()
        val manager = WatchPinSyncManager(
            pinSender = FakeTimelinePinSender(),
            repository = InMemorySentPinRepository(),
            messenger = messenger,
            onOpenPhoneUi = { throw IllegalStateException("Unexpected launcher crash") },
            clock = fixedClock,
        )

        val outcome = manager.handleWatchMessage(
            data = mapOf(
                WatchAppProtocol.KEY_MSG_TYPE to PebbleDictionaryItem.UInt8(WatchAppProtocol.MSG_WATCH_REQ_OPEN_PHONE),
            ),
            watch = watch,
        )

        assertTrue(outcome is WatchHandleOutcome.OpenPhoneHandled)
        assertTrue((outcome as WatchHandleOutcome.OpenPhoneHandled).result is PhoneUiOpenResult.Blocked)
        val feedback = messenger.sentMessages.single().data
        assertEquals(PebbleDictionaryItem.UInt8(0), feedback[WatchAppProtocol.KEY_STATUS_OK])
        assertEquals(
            PebbleDictionaryItem.Text("Open app on phone"),
            feedback[WatchAppProtocol.KEY_STATUS_TEXT],
        )
    }

    @Test
    fun `syncRecentPins includes both manual and auto-created pins and rescheduling preserves notification source`() = runTest {
        val manualStart = Instant.parse("2026-11-22T14:00:00Z").toEpochMilli()
        val autoStart = Instant.parse("2026-11-22T16:00:00Z").toEpochMilli()
        val repo = InMemorySentPinRepository(
            initial = listOf(
                SentPinRecord(
                    id = "ppm-manual-1",
                    title = "Dentist",
                    startTimeMillis = manualStart,
                    source = EventSource.MANUAL.name,
                    sentAtMillis = 1_000L,
                ),
                SentPinRecord(
                    id = "ppm-notif-1",
                    title = "Flight LH123",
                    description = "Gate B12",
                    startTimeMillis = autoStart,
                    source = EventSource.NOTIFICATION.name,
                    sourceKey = "0|com.example.flights|42|null|1000",
                    sentAtMillis = 2_000L,
                ),
            ),
        )
        val pinSender = FakeTimelinePinSender()
        val messenger = RecordingWatchAppMessenger()
        val manager = WatchPinSyncManager(
            pinSender = pinSender,
            repository = repo,
            messenger = messenger,
            onOpenPhoneUi = { PhoneUiOpenResult.OpenedDirect(DirectLaunchReason.APP_IN_FOREGROUND) },
            clock = fixedClock,
        )

        val syncResult = manager.syncRecentPins(targetWatch = watch)
        assertEquals(WatchSyncResult.Synced(2), syncResult)
        assertEquals(
            listOf("ppm-notif-1", "ppm-manual-1"),
            messenger.sentMessages.drop(1).map {
                (it.data[WatchAppProtocol.KEY_PIN_ID] as PebbleDictionaryItem.Text).value
            },
        )

        val outcome = manager.handleWatchMessage(
            data = mapOf(
                WatchAppProtocol.KEY_MSG_TYPE to PebbleDictionaryItem.UInt8(WatchAppProtocol.MSG_WATCH_REQ_RESCHEDULE),
                WatchAppProtocol.KEY_PIN_ID to PebbleDictionaryItem.Text("ppm-notif-1"),
                WatchAppProtocol.KEY_DELTA_MINUTES to PebbleDictionaryItem.Int32(30),
                WatchAppProtocol.KEY_DELTA_DAYS to PebbleDictionaryItem.Int32(0),
            ),
            watch = watch,
        )

        val expectedShifted = Instant.parse("2026-11-22T16:30:00Z").toEpochMilli()
        assertEquals(WatchHandleOutcome.PinRescheduled("ppm-notif-1", expectedShifted), outcome)
        val updated = repo.getPinById("ppm-notif-1")!!
        assertEquals(EventSource.NOTIFICATION.name, updated.source)
        assertEquals("0|com.example.flights|42|null|1000", updated.sourceKey)
        assertEquals(expectedShifted, updated.startTimeMillis)
    }
}

private data class RecordedWatchMessage(
    val data: PebbleDictionary,
    val targetWatch: WatchIdentifier?,
)

private class RecordingWatchAppMessenger(
    private val nextResult: WatchMessageResult = WatchMessageResult.Delivered,
) : WatchAppMessenger {
    val sentMessages = mutableListOf<RecordedWatchMessage>()

    override suspend fun sendToWatch(
        data: PebbleDictionary,
        targetWatch: WatchIdentifier?,
    ): WatchMessageResult {
        sentMessages += RecordedWatchMessage(data, targetWatch)
        return nextResult
    }
}

private class FakeTimelinePinSender(
    private val sendResult: PinSendResult? = null,
    private val deleteResult: PinDeleteResult? = null,
) : TimelinePinSender {
    val sentEvents = mutableListOf<TimelineEvent>()
    val deletedIds = mutableListOf<String>()

    override suspend fun sendPin(event: TimelineEvent): PinSendResult {
        sentEvents += event
        return sendResult ?: PinSendResult.Success(event.id)
    }

    override suspend fun deletePin(pinId: String): PinDeleteResult {
        deletedIds += pinId
        return deleteResult ?: PinDeleteResult.Success(pinId)
    }

    override suspend fun getCompanionStatus(): PebbleCompanionStatus =
        PebbleCompanionStatus.Available("coredevices.coreapp", listOf("coredevices.coreapp"))

    override fun observeConnectedWatches(): Flow<List<ConnectedPebbleWatch>> = flowOf(emptyList())
}

private class InMemorySentPinRepository(
    initial: List<SentPinRecord> = emptyList(),
) : SentPinRepository {
    private val state = MutableStateFlow(initial.sortedByDescending { it.sentAtMillis })

    override fun observeSentPins(): Flow<List<SentPinRecord>> = state

    override suspend fun saveSentPin(record: SentPinRecord) {
        state.value = (listOf(record) + state.value.filterNot { it.id == record.id })
            .sortedByDescending { it.sentAtMillis }
    }

    override suspend fun getPinById(id: String): SentPinRecord? = state.value.firstOrNull { it.id == id }

    override suspend fun getPinBySourceKey(sourceKey: String): SentPinRecord? =
        state.value.firstOrNull { it.sourceKey == sourceKey }

    override suspend fun deletePinRecord(id: String) {
        state.value = state.value.filterNot { it.id == id }
    }

    override suspend fun clearAll() {
        state.value = emptyList()
    }
}
