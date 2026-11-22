package xyz.hidasy.pebblepinmanager.pebble

import io.rebble.pebblekit2.client.PebbleAndroidAppPicker
import io.rebble.pebblekit2.client.PebbleInfoRetriever
import io.rebble.pebblekit2.client.PebbleSender
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.TimelinePin
import io.rebble.pebblekit2.common.model.TimelineResult
import io.rebble.pebblekit2.common.model.TransmissionResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import io.rebble.pebblekit2.model.ConnectedWatch
import io.rebble.pebblekit2.model.Watchapp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.hidasy.pebblepinmanager.domain.TimelineEvent
import java.time.Instant
import java.util.UUID

class PebbleKit2PinSenderTest {
    private val watchappUuid = UUID.fromString("e4b7c3a1-8f2d-4c6e-9a51-7b3d2e8f1042")

    @Test
    fun `sendPin forwards mapped TimelinePin to PebbleSender and returns Success`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fakeSender = FakePebbleSender(insertResult = TimelineResult.Success)
        val sender = PebbleKit2PinSender(
            pebbleSender = fakeSender,
            infoRetriever = FakePebbleInfoRetriever(),
            appPicker = FakeAppPicker(selected = "coredevices.coreapp", eligible = listOf("coredevices.coreapp")),
            watchappUuid = watchappUuid,
            ioDispatcher = dispatcher,
        )

        val event = TimelineEvent(
            id = "ppm-001",
            title = "Coffee with Alex",
            description = "Corner cafe",
            startTimeMillis = Instant.parse("2026-11-21T16:00:00Z").toEpochMilli(),
        )

        val result = sender.sendPin(event)

        assertEquals(PinSendResult.Success("ppm-001"), result)
        assertEquals(watchappUuid, fakeSender.lastWatchappUuid)
        assertEquals("ppm-001", fakeSender.lastInsertedPin?.id)
        assertEquals("Coffee with Alex", fakeSender.lastInsertedPin?.layout?.title)
        assertEquals("Corner cafe", fakeSender.lastInsertedPin?.layout?.body)
    }

    @Test
    fun `sendPin handles PebbleSender exception gracefully`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fakeSender = FakePebbleSender(throwOnInsert = IllegalStateException("Binder died"))
        val sender = PebbleKit2PinSender(
            pebbleSender = fakeSender,
            infoRetriever = FakePebbleInfoRetriever(),
            appPicker = FakeAppPicker(),
            watchappUuid = watchappUuid,
            ioDispatcher = dispatcher,
        )

        val result = sender.sendPin(
            TimelineEvent(
                id = "ppm-002",
                title = "Broken IPC",
                startTimeMillis = Instant.parse("2026-11-21T16:00:00Z").toEpochMilli(),
            ),
        )

        assertTrue(result is PinSendResult.UnexpectedError)
        assertTrue((result as PinSendResult.UnexpectedError).message.contains("Binder died"))
    }

    @Test
    fun `sendPin updates an existing pin in place by reusing the same pin id`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fakeSender = FakePebbleSender(insertResult = TimelineResult.Success)
        val sender = PebbleKit2PinSender(
            pebbleSender = fakeSender,
            infoRetriever = FakePebbleInfoRetriever(),
            appPicker = FakeAppPicker(selected = "coredevices.coreapp", eligible = listOf("coredevices.coreapp")),
            watchappUuid = watchappUuid,
            ioDispatcher = dispatcher,
        )

        val original = TimelineEvent(
            id = "ppm-update-1",
            title = "Dentist",
            description = "Room 101",
            startTimeMillis = Instant.parse("2026-11-21T16:00:00Z").toEpochMilli(),
        )
        val updated = original.copy(
            title = "Dentist (Rescheduled)",
            description = "Room 204",
            startTimeMillis = Instant.parse("2026-11-21T17:30:00Z").toEpochMilli(),
        )

        assertEquals(PinSendResult.Success("ppm-update-1"), sender.sendPin(original))
        assertEquals(PinSendResult.Success("ppm-update-1"), sender.sendPin(updated))

        assertEquals(2, fakeSender.insertedPins.size)
        assertEquals("ppm-update-1", fakeSender.insertedPins[0].id)
        assertEquals("ppm-update-1", fakeSender.insertedPins[1].id)
        assertEquals("Dentist (Rescheduled)", fakeSender.insertedPins[1].layout.title)
        assertEquals("Room 204", fakeSender.insertedPins[1].layout.body)
        assertEquals(
            Instant.parse("2026-11-21T17:30:00Z").toEpochMilli(),
            fakeSender.insertedPins[1].startTime.toEpochMilliseconds(),
        )
    }

    @Test
    fun `deletePin delegates pin id and maps Success`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fakeSender = FakePebbleSender(deleteResult = TimelineResult.Success)
        val sender = PebbleKit2PinSender(
            pebbleSender = fakeSender,
            infoRetriever = FakePebbleInfoRetriever(),
            appPicker = FakeAppPicker(),
            watchappUuid = watchappUuid,
            ioDispatcher = dispatcher,
        )

        val result = sender.deletePin("  ppm-777  ")

        assertEquals(PinDeleteResult.Success("ppm-777"), result)
        assertEquals(watchappUuid, fakeSender.lastWatchappUuid)
        assertEquals("ppm-777", fakeSender.lastDeletedPinId)
    }

    @Test
    fun `deletePin delegates pin id and maps UnknownPin`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fakeSender = FakePebbleSender(deleteResult = TimelineResult.FailedUnknownPin)
        val sender = PebbleKit2PinSender(
            pebbleSender = fakeSender,
            infoRetriever = FakePebbleInfoRetriever(),
            appPicker = FakeAppPicker(),
            watchappUuid = watchappUuid,
            ioDispatcher = dispatcher,
        )

        val result = sender.deletePin("  ppm-999  ")

        assertEquals(PinDeleteResult.UnknownPin("ppm-999"), result)
        assertEquals("ppm-999", fakeSender.lastDeletedPinId)
    }

    @Test
    fun `deletePin rejects blank pin id without calling PebbleSender`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val fakeSender = FakePebbleSender()
        val sender = PebbleKit2PinSender(
            pebbleSender = fakeSender,
            infoRetriever = FakePebbleInfoRetriever(),
            appPicker = FakeAppPicker(),
            watchappUuid = watchappUuid,
            ioDispatcher = dispatcher,
        )

        val result = sender.deletePin("   ")

        assertTrue(result is PinDeleteResult.UnexpectedError)
        assertEquals(null, fakeSender.lastDeletedPinId)
    }

    @Test
    fun `deletePin maps SecurityException to NoPermissions and other exceptions to UnexpectedError`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val deniedSender = PebbleKit2PinSender(
            pebbleSender = FakePebbleSender(throwOnDelete = SecurityException("Not allowed")),
            infoRetriever = FakePebbleInfoRetriever(),
            appPicker = FakeAppPicker(),
            watchappUuid = watchappUuid,
            ioDispatcher = dispatcher,
        )
        assertEquals(
            PinDeleteResult.NoPermissions(watchappUuid.toString()),
            deniedSender.deletePin("ppm-denied"),
        )

        val brokenSender = PebbleKit2PinSender(
            pebbleSender = FakePebbleSender(throwOnDelete = IllegalStateException("Delete binder died")),
            infoRetriever = FakePebbleInfoRetriever(),
            appPicker = FakeAppPicker(),
            watchappUuid = watchappUuid,
            ioDispatcher = dispatcher,
        )
        val brokenResult = brokenSender.deletePin("ppm-broken")
        assertTrue(brokenResult is PinDeleteResult.UnexpectedError)
        assertTrue((brokenResult as PinDeleteResult.UnexpectedError).message.contains("Delete binder died"))
    }

    @Test
    fun `getCompanionStatus distinguishes available vs no app installed`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val availableSender = PebbleKit2PinSender(
            pebbleSender = FakePebbleSender(),
            infoRetriever = FakePebbleInfoRetriever(),
            appPicker = FakeAppPicker(
                selected = "coredevices.coreapp",
                eligible = listOf("coredevices.coreapp"),
            ),
            ioDispatcher = dispatcher,
        )
        val unavailableSender = PebbleKit2PinSender(
            pebbleSender = FakePebbleSender(),
            infoRetriever = FakePebbleInfoRetriever(),
            appPicker = FakeAppPicker(selected = null, eligible = emptyList()),
            ioDispatcher = dispatcher,
        )

        val available = availableSender.getCompanionStatus()
        assertEquals(
            PebbleCompanionStatus.Available("coredevices.coreapp", listOf("coredevices.coreapp")),
            available,
        )

        val unavailable = unavailableSender.getCompanionStatus()
        assertTrue(unavailable is PebbleCompanionStatus.Unavailable)
        assertTrue((unavailable as PebbleCompanionStatus.Unavailable).reason.contains("Core Devices Pebble"))
    }

    @Test
    fun `observeConnectedWatches emits mapped basalt and emery watches and survives provider errors`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val watches = listOf(
            ConnectedWatch(WatchIdentifier("1"), "Time", "basalt", "snowy", 4, 3, 0, null),
            ConnectedWatch(WatchIdentifier("2"), "Time 2", "emery", "robert", 4, 9, 0, "beta"),
        )
        val sender = PebbleKit2PinSender(
            pebbleSender = FakePebbleSender(),
            infoRetriever = FakePebbleInfoRetriever(watchesFlow = flowOf(watches)),
            appPicker = FakeAppPicker(),
            ioDispatcher = dispatcher,
        )

        val mapped = sender.observeConnectedWatches().first()
        assertEquals(2, mapped.size)
        assertEquals(PebbleHardwarePlatform.BASALT, mapped[0].platform)
        assertEquals(PebbleHardwarePlatform.EMERY, mapped[1].platform)

        val failingSender = PebbleKit2PinSender(
            pebbleSender = FakePebbleSender(),
            infoRetriever = FakePebbleInfoRetriever(
                watchesFlow = flow { throw SecurityException("Provider unavailable") },
            ),
            appPicker = FakeAppPicker(),
            ioDispatcher = dispatcher,
        )
        assertEquals(emptyList<ConnectedPebbleWatch>(), failingSender.observeConnectedWatches().first())
    }
}

private class FakePebbleSender(
    private val insertResult: TimelineResult = TimelineResult.Success,
    private val deleteResult: TimelineResult = TimelineResult.Success,
    private val throwOnInsert: Throwable? = null,
    private val throwOnDelete: Throwable? = null,
) : PebbleSender {
    var lastWatchappUuid: UUID? = null
    var lastInsertedPin: TimelinePin? = null
    val insertedPins: MutableList<TimelinePin> = mutableListOf()
    var lastDeletedPinId: String? = null

    override suspend fun sendDataToPebble(
        watchappUUID: UUID,
        data: PebbleDictionary,
        watches: List<WatchIdentifier>?,
    ): Map<WatchIdentifier, TransmissionResult>? = emptyMap()

    override suspend fun startAppOnTheWatch(
        watchappUUID: UUID,
        watches: List<WatchIdentifier>?,
    ): Map<WatchIdentifier, TransmissionResult>? = emptyMap()

    override suspend fun stopAppOnTheWatch(
        watchappUUID: UUID,
        watches: List<WatchIdentifier>?,
    ): Map<WatchIdentifier, TransmissionResult>? = emptyMap()

    override suspend fun insertTimelinePin(
        watchappUUID: UUID,
        timelinePin: TimelinePin,
    ): TimelineResult {
        throwOnInsert?.let { throw it }
        lastWatchappUuid = watchappUUID
        lastInsertedPin = timelinePin
        insertedPins += timelinePin
        return insertResult
    }

    override suspend fun deleteTimelinePin(
        watchappUUID: UUID,
        id: String,
    ): TimelineResult {
        throwOnDelete?.let { throw it }
        lastWatchappUuid = watchappUUID
        lastDeletedPinId = id
        return deleteResult
    }

    override fun close() = Unit
}

private class FakePebbleInfoRetriever(
    private val watchesFlow: Flow<List<ConnectedWatch>> = flowOf(emptyList()),
) : PebbleInfoRetriever {
    override fun getConnectedWatches(): Flow<List<ConnectedWatch>> = watchesFlow
    override fun getActiveApp(watch: WatchIdentifier): Flow<Watchapp?> = flowOf(null)
}

private class FakeAppPicker(
    private var selected: String? = null,
    private val eligible: List<String> = emptyList(),
) : PebbleAndroidAppPicker {
    override var enableAutoSelect: Boolean = true
    override suspend fun getCurrentlySelectedApp(): String? = selected
    override suspend fun selectApp(packageName: String?) {
        selected = packageName
    }
    override fun getAllEligibleApps(): List<String> = eligible
}
