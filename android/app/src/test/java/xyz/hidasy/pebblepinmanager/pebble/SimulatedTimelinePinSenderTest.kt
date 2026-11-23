package xyz.hidasy.pebblepinmanager.pebble

import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.hidasy.pebblepinmanager.data.SentPinRecord
import xyz.hidasy.pebblepinmanager.data.SentPinRepository
import xyz.hidasy.pebblepinmanager.data.SimulatedTimelineAction
import xyz.hidasy.pebblepinmanager.data.SimulatedTimelineActionType
import xyz.hidasy.pebblepinmanager.data.SimulatedTimelinePin
import xyz.hidasy.pebblepinmanager.data.SimulatedTimelineRepository
import xyz.hidasy.pebblepinmanager.data.SimulatedTimelineSnapshot
import xyz.hidasy.pebblepinmanager.domain.EventSource
import xyz.hidasy.pebblepinmanager.domain.PinAccentColor
import xyz.hidasy.pebblepinmanager.domain.PinIcon
import xyz.hidasy.pebblepinmanager.domain.TimelineEvent
import xyz.hidasy.pebblepinmanager.notifications.NotificationPinCandidate
import xyz.hidasy.pebblepinmanager.notifications.NotificationPinPipeline
import java.time.Instant
import java.util.UUID

class SimulatedTimelinePinSenderTest {
    private val watchappUuid = UUID.fromString("e4b7c3a1-8f2d-4c6e-9a51-7b3d2e8f1042")

    @Test
    fun `formatter renders PebbleKit 2 fields accurately in JSON`() {
        val event = TimelineEvent(
            id = "ppm-json-1",
            title = "Dentist",
            description = "Room 302",
            startTimeMillis = Instant.parse("2026-11-23T15:30:00Z").toEpochMilli(),
            durationMinutes = 45,
            iconUri = PinIcon.ALARM_CLOCK.uri,
            backgroundColor = PinAccentColor.ORANGE.hexCode,
        )

        val json = SimulatedTimelinePayloadFormatter.formatPinPayloadJson(event, watchappUuid)

        assertTrue(json.contains("\"watchappUuid\": \"$watchappUuid\""))
        assertTrue(json.contains("\"id\": \"ppm-json-1\""))
        assertTrue(json.contains("\"time\": \"2026-11-23T15:30:00Z\""))
        assertTrue(json.contains("\"duration\": 45"))
        assertTrue(json.contains("\"type\": \"genericPin\""))
        assertTrue(json.contains("\"title\": \"Dentist\""))
        assertTrue(json.contains("\"body\": \"Room 302\""))
        assertTrue(json.contains("\"tinyIcon\": \"system://images/ALARM_CLOCK\""))
        assertTrue(json.contains("\"backgroundColor\": \"#FF5500\""))
    }

    @Test
    fun `simulated sender captures insert, update, delete, and reports SimulatedNoWatch status`() = runTest {
        val repo = InMemorySimulatedTimelineRepository()
        var now = 1_000L
        val sender = SimulatedTimelinePinSender(
            simulatedRepository = repo,
            watchappUuid = watchappUuid,
            clockMillis = { now },
        )

        assertEquals(PebbleCompanionStatus.SimulatedNoWatch, sender.getCompanionStatus())
        assertTrue(sender.observeConnectedWatches().first().isEmpty())

        val event = TimelineEvent(
            id = "ppm-sim-1",
            title = "Flight Boarding",
            description = "Gate B4",
            startTimeMillis = Instant.parse("2026-11-23T18:00:00Z").toEpochMilli(),
            durationMinutes = 30,
            iconUri = PinIcon.SCHEDULED_FLIGHT.uri,
            backgroundColor = PinAccentColor.SKY.hexCode,
        )

        assertEquals(PinSendResult.Success("ppm-sim-1"), sender.sendPin(event))
        assertEquals(1, repo.getSnapshot().activePins.size)
        assertEquals(SimulatedTimelineActionType.INSERT, repo.getSnapshot().actionLog.first().type)

        now = 2_000L
        assertEquals(
            PinSendResult.Success("ppm-sim-1"),
            sender.sendPin(event.copy(title = "Flight Boarding (Delayed)")),
        )
        assertEquals(1, repo.getSnapshot().activePins.size)
        assertEquals("Flight Boarding (Delayed)", repo.getSnapshot().activePins.single().title)
        assertEquals(SimulatedTimelineActionType.UPDATE, repo.getSnapshot().actionLog.first().type)

        now = 3_000L
        assertEquals(PinDeleteResult.Success("ppm-sim-1"), sender.deletePin("ppm-sim-1"))
        assertTrue(repo.getSnapshot().activePins.isEmpty())
        assertEquals(SimulatedTimelineActionType.DELETE, repo.getSnapshot().actionLog.first().type)

        assertEquals(PinDeleteResult.UnknownPin("ppm-sim-1"), sender.deletePin("ppm-sim-1"))
        assertTrue(sender.deletePin("   ") is PinDeleteResult.UnexpectedError)
    }

    @Test
    fun `switchable sender routes calls and watch stream based on runWithoutWatch mode`() = runTest {
        val watch = ConnectedPebbleWatch(
            id = "w1",
            name = "My Pebble",
            platformCode = "emery",
            platform = PebbleHardwarePlatform.EMERY,
            revision = "robert",
            firmwareVersion = "4.9.0",
        )
        val realSender = CountingTimelinePinSender(
            companionStatus = PebbleCompanionStatus.Available("coredevices.coreapp", listOf("coredevices.coreapp")),
            watches = listOf(watch),
        )
        val simulatedRepo = InMemorySimulatedTimelineRepository()
        val simulatedSender = SimulatedTimelinePinSender(simulatedRepo, watchappUuid)
        val modeFlow = MutableStateFlow(false)

        val switchable = SwitchableTimelinePinSender(
            realSender = realSender,
            simulatedSender = simulatedSender,
            runWithoutWatchFlow = modeFlow,
            isRunWithoutWatch = { modeFlow.value },
        )

        // Watch mode active
        assertTrue(switchable.getCompanionStatus() is PebbleCompanionStatus.Available)
        assertEquals(listOf(watch), switchable.observeConnectedWatches().first())
        val event1 = TimelineEvent(
            id = "ppm-real",
            title = "Real Pin",
            startTimeMillis = Instant.parse("2026-11-23T15:00:00Z").toEpochMilli(),
        )
        assertEquals(PinSendResult.Success("ppm-real"), switchable.sendPin(event1))
        assertEquals(1, realSender.sentIds.size)
        assertTrue(simulatedRepo.getSnapshot().activePins.isEmpty())

        // Switch to watchless mode at runtime
        modeFlow.value = true
        assertEquals(PebbleCompanionStatus.SimulatedNoWatch, switchable.getCompanionStatus())
        assertTrue(switchable.observeConnectedWatches().first().isEmpty())

        val event2 = TimelineEvent(
            id = "ppm-sim",
            title = "Simulated Pin",
            startTimeMillis = Instant.parse("2026-11-23T16:00:00Z").toEpochMilli(),
        )
        assertEquals(PinSendResult.Success("ppm-sim"), switchable.sendPin(event2))
        assertEquals(1, realSender.sentIds.size)
        assertEquals(listOf("ppm-sim"), simulatedRepo.getSnapshot().activePins.map { it.id })

        assertEquals(PinDeleteResult.Success("ppm-sim"), switchable.deletePin("ppm-sim"))
        assertEquals(0, realSender.deletedIds.size)
        assertTrue(simulatedRepo.getSnapshot().activePins.isEmpty())
    }

    @Test
    fun `switchable watch messenger skips watch AppMessages in watchless mode`() = runTest {
        var delegatedCount = 0
        val realMessenger = object : WatchAppMessenger {
            override suspend fun sendToWatch(
                data: PebbleDictionary,
                targetWatch: WatchIdentifier?,
            ): WatchMessageResult {
                delegatedCount++
                return WatchMessageResult.Delivered
            }
        }
        var noWatch = true
        val switchable = SwitchableWatchAppMessenger(
            realMessenger = realMessenger,
            isRunWithoutWatch = { noWatch },
        )

        assertEquals(WatchMessageResult.WatchNotConnected, switchable.sendToWatch(emptyMap()))
        assertEquals(0, delegatedCount)

        noWatch = false
        assertEquals(WatchMessageResult.Delivered, switchable.sendToWatch(emptyMap()))
        assertEquals(1, delegatedCount)
    }

    @Test
    fun `notification auto-pin pipeline delivers and removes pins cleanly in watchless mode`() = runTest {
        val now = Instant.parse("2026-11-23T12:00:00Z").toEpochMilli()
        val simulatedRepo = InMemorySimulatedTimelineRepository()
        val simulatedSender = SimulatedTimelinePinSender(
            simulatedRepository = simulatedRepo,
            watchappUuid = watchappUuid,
            clockMillis = { now },
        )
        val sentRepo = InMemorySentPinRepo()
        val pipeline = NotificationPinPipeline(
            mapper = { candidate, existingId ->
                TimelineEvent(
                    id = existingId ?: "ppm-auto-1",
                    title = candidate.title,
                    description = candidate.text,
                    startTimeMillis = candidate.eventTimeMillis,
                    source = EventSource.NOTIFICATION,
                )
            },
            pinSender = simulatedSender,
            repository = sentRepo,
            clockMillis = { now },
        )

        val candidate = NotificationPinCandidate(
            sourcePackage = "com.example.calendar",
            notificationKey = "0|com.example.calendar|42",
            title = "Team Sync",
            text = "Conference room A",
            eventTimeMillis = now + 3_600_000L,
        )

        pipeline.process(candidate)
        val snapshot = simulatedRepo.getSnapshot()
        assertEquals(1, snapshot.activePins.size)
        assertEquals("Team Sync", snapshot.activePins.single().title)
        assertTrue(snapshot.activePins.single().isAutoPin)
        assertEquals("0|com.example.calendar|42", snapshot.activePins.single().sourceKey)

        pipeline.processRemoval("0|com.example.calendar|42")
        assertTrue(simulatedRepo.getSnapshot().activePins.isEmpty())
        assertEquals(SimulatedTimelineActionType.DELETE, simulatedRepo.getSnapshot().actionLog.first().type)
    }
}

internal class InMemorySimulatedTimelineRepository : SimulatedTimelineRepository {
    private val state = MutableStateFlow(SimulatedTimelineSnapshot())

    override fun observeSnapshot(): Flow<SimulatedTimelineSnapshot> = state

    override suspend fun getSnapshot(): SimulatedTimelineSnapshot = state.value

    override suspend fun recordUpsert(
        pin: SimulatedTimelinePin,
        timestampMillis: Long,
    ): SimulatedTimelineActionType {
        val current = state.value
        val existing = current.activePins.firstOrNull { it.id == pin.id }
        val type = if (existing != null) {
            SimulatedTimelineActionType.UPDATE
        } else {
            SimulatedTimelineActionType.INSERT
        }
        val storedPin = pin.copy(
            createdAtMillis = existing?.createdAtMillis ?: pin.createdAtMillis,
            updatedAtMillis = timestampMillis,
        )
        val updatedPins = (current.activePins.filterNot { it.id == pin.id } + storedPin)
            .sortedWith(compareBy<SimulatedTimelinePin> { it.startTimeMillis }.thenBy { it.updatedAtMillis })
        val entry = SimulatedTimelineAction(
            type = type,
            pinId = storedPin.id,
            watchappUuid = storedPin.watchappUuid,
            title = storedPin.title,
            source = storedPin.source,
            sourceKey = storedPin.sourceKey,
            startTimeMillis = storedPin.startTimeMillis,
            payloadJson = storedPin.payloadJson,
            timestampMillis = timestampMillis,
        )
        state.value = SimulatedTimelineSnapshot(
            activePins = updatedPins,
            actionLog = listOf(entry) + current.actionLog,
        )
        return type
    }

    override suspend fun recordDelete(
        pinId: String,
        watchappUuid: String,
        timestampMillis: Long,
    ): Boolean {
        val current = state.value
        val existing = current.activePins.firstOrNull { it.id == pinId }
        val entry = SimulatedTimelineAction(
            type = SimulatedTimelineActionType.DELETE,
            pinId = pinId,
            watchappUuid = watchappUuid,
            title = existing?.title,
            source = existing?.source,
            sourceKey = existing?.sourceKey,
            startTimeMillis = existing?.startTimeMillis,
            payloadJson = existing?.payloadJson,
            timestampMillis = timestampMillis,
        )
        state.value = SimulatedTimelineSnapshot(
            activePins = current.activePins.filterNot { it.id == pinId },
            actionLog = listOf(entry) + current.actionLog,
        )
        return existing != null
    }

    override suspend fun clearActivePins(): List<String> {
        val ids = state.value.activePins.map { it.id }
        state.value = state.value.copy(activePins = emptyList())
        return ids
    }

    override suspend fun clearActionLog() {
        state.value = state.value.copy(actionLog = emptyList())
    }

    override suspend fun clearAll(): List<String> {
        val ids = state.value.activePins.map { it.id }
        state.value = SimulatedTimelineSnapshot()
        return ids
    }
}

private class CountingTimelinePinSender(
    private val companionStatus: PebbleCompanionStatus,
    private val watches: List<ConnectedPebbleWatch> = emptyList(),
) : TimelinePinSender {
    val sentIds = mutableListOf<String>()
    val deletedIds = mutableListOf<String>()

    override suspend fun sendPin(event: TimelineEvent): PinSendResult {
        sentIds += event.id
        return PinSendResult.Success(event.id)
    }

    override suspend fun deletePin(pinId: String): PinDeleteResult {
        deletedIds += pinId
        return PinDeleteResult.Success(pinId)
    }

    override suspend fun getCompanionStatus(): PebbleCompanionStatus = companionStatus

    override fun observeConnectedWatches(): Flow<List<ConnectedPebbleWatch>> = flowOf(watches)
}

private class InMemorySentPinRepo : SentPinRepository {
    private val state = MutableStateFlow<List<SentPinRecord>>(emptyList())

    override fun observeSentPins(): Flow<List<SentPinRecord>> = state

    override suspend fun saveSentPin(record: SentPinRecord) {
        state.value = listOf(record) + state.value.filterNot { it.id == record.id }
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
