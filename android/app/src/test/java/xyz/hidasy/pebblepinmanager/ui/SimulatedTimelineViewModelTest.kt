package xyz.hidasy.pebblepinmanager.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.hidasy.pebblepinmanager.data.SentPinRecord
import xyz.hidasy.pebblepinmanager.data.SentPinRepository
import xyz.hidasy.pebblepinmanager.domain.EventSource
import xyz.hidasy.pebblepinmanager.domain.TimelineEvent
import xyz.hidasy.pebblepinmanager.pebble.InMemorySimulatedTimelineRepository
import xyz.hidasy.pebblepinmanager.pebble.SimulatedTimelinePinSender
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class SimulatedTimelineViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val fixedClock = Clock.fixed(
        Instant.parse("2026-11-23T12:00:00Z"),
        ZoneId.of("UTC"),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `groups pins into Today, Tomorrow, Future, and Past and filters by source`() = runTest {
        val simRepo = InMemorySimulatedTimelineRepository()
        val sentRepo = FakeTimelineSentPinRepository()
        val sender = SimulatedTimelinePinSender(simRepo, clockMillis = { fixedClock.millis() })

        val pastEvent = TimelineEvent(
            id = "ppm-past",
            title = "Morning Coffee",
            startTimeMillis = Instant.parse("2026-11-23T09:00:00Z").toEpochMilli(),
        )
        val ongoingEvent = TimelineEvent(
            id = "ppm-now",
            title = "Lunch Meeting",
            startTimeMillis = Instant.parse("2026-11-23T11:45:00Z").toEpochMilli(),
            durationMinutes = 60,
        )
        val tomorrowAuto = TimelineEvent(
            id = "ppm-tomorrow",
            title = "Flight Check-in",
            startTimeMillis = Instant.parse("2026-11-24T10:00:00Z").toEpochMilli(),
            source = EventSource.NOTIFICATION,
            sourceKey = "0|com.airline|1",
        )
        val futureEvent = TimelineEvent(
            id = "ppm-future",
            title = "Conference Keynote",
            startTimeMillis = Instant.parse("2026-11-27T14:00:00Z").toEpochMilli(),
        )

        listOf(pastEvent, ongoingEvent, tomorrowAuto, futureEvent).forEach { event ->
            sender.sendPin(event)
            sentRepo.saveSentPin(SentPinRecord.fromTimelineEvent(event, sentAtMillis = fixedClock.millis()))
        }

        val vm = SimulatedTimelineViewModel(
            simulatedTimelineRepository = simRepo,
            sentPinRepository = sentRepo,
            pinSender = sender,
            clock = fixedClock,
        )
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(4, state.activePinCount)
        assertEquals(3, state.manualPinCount)
        assertEquals(1, state.autoPinCount)
        assertEquals(3, state.upcomingCount)
        assertEquals(1, state.pastCount)

        val labels = state.timelineSections.map { it.label }
        assertEquals(listOf("Today", "Tomorrow", "Fri, Nov 27, 2026", "Past"), labels)

        val ongoingPin = state.timelineSections.first().pins.single()
        assertEquals("ppm-now", ongoingPin.id)
        assertTrue(state.isPinOngoing(ongoingPin))
        assertFalse(state.isPinInPast(ongoingPin))

        // Filter to Auto-Pins only
        vm.onSelectSourceFilter(TimelineSourceFilter.AUTO_PIN)
        assertEquals(1, vm.uiState.value.filteredActivePins.size)
        assertEquals("ppm-tomorrow", vm.uiState.value.filteredActivePins.single().id)

        // Filter to Manual only
        vm.onSelectSourceFilter(TimelineSourceFilter.MANUAL)
        assertEquals(3, vm.uiState.value.filteredActivePins.size)

        // Toggle payload expansion
        vm.onTogglePinExpanded("ppm-now")
        assertTrue("ppm-now" in vm.uiState.value.expandedPinIds)
        vm.onTogglePinExpanded("ppm-now")
        assertFalse("ppm-now" in vm.uiState.value.expandedPinIds)
    }

    @Test
    fun `deletePin removes from simulated timeline, logs DELETE, and cleans sent history`() = runTest {
        val simRepo = InMemorySimulatedTimelineRepository()
        val sentRepo = FakeTimelineSentPinRepository()
        val sender = SimulatedTimelinePinSender(simRepo, clockMillis = { fixedClock.millis() })

        val event = TimelineEvent(
            id = "ppm-del",
            title = "Dentist",
            startTimeMillis = Instant.parse("2026-11-23T15:00:00Z").toEpochMilli(),
        )
        sender.sendPin(event)
        sentRepo.saveSentPin(SentPinRecord.fromTimelineEvent(event, sentAtMillis = fixedClock.millis()))

        val vm = SimulatedTimelineViewModel(
            simulatedTimelineRepository = simRepo,
            sentPinRepository = sentRepo,
            pinSender = sender,
            clock = fixedClock,
        )
        advanceUntilIdle()

        vm.deletePin("ppm-del")
        advanceUntilIdle()

        assertEquals(0, vm.uiState.value.activePinCount)
        assertEquals(2, vm.uiState.value.actionLogCount) // INSERT + DELETE
        assertEquals(null, sentRepo.getPinById("ppm-del"))
        assertTrue(vm.uiState.value.feedback is SendFeedback.Success)
    }

    @Test
    fun `clearActionLog keeps active pins while clearAll clears both and removes matching sent pins`() = runTest {
        val simRepo = InMemorySimulatedTimelineRepository()
        val sentRepo = FakeTimelineSentPinRepository()
        val sender = SimulatedTimelinePinSender(simRepo, clockMillis = { fixedClock.millis() })

        val simEvent = TimelineEvent(
            id = "ppm-sim-only",
            title = "Simulated Pin",
            startTimeMillis = Instant.parse("2026-11-23T15:00:00Z").toEpochMilli(),
        )
        val realRecord = SentPinRecord(
            id = "ppm-real-earlier",
            title = "Real Watch Pin",
            startTimeMillis = Instant.parse("2026-11-23T16:00:00Z").toEpochMilli(),
            sentAtMillis = fixedClock.millis(),
        )
        sender.sendPin(simEvent)
        sentRepo.saveSentPin(SentPinRecord.fromTimelineEvent(simEvent, sentAtMillis = fixedClock.millis()))
        sentRepo.saveSentPin(realRecord)

        val vm = SimulatedTimelineViewModel(
            simulatedTimelineRepository = simRepo,
            sentPinRepository = sentRepo,
            pinSender = sender,
            clock = fixedClock,
        )
        advanceUntilIdle()

        vm.clearActionLog()
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.activePinCount)
        assertEquals(0, vm.uiState.value.actionLogCount)

        vm.clearAll()
        advanceUntilIdle()
        assertEquals(0, vm.uiState.value.activePinCount)
        assertEquals(0, vm.uiState.value.actionLogCount)
        // Simulated pin removed from sentRepo, while pre-existing real watch pin stays intact
        assertEquals(null, sentRepo.getPinById("ppm-sim-only"))
        assertEquals("Real Watch Pin", sentRepo.getPinById("ppm-real-earlier")?.title)
    }
}

private class FakeTimelineSentPinRepository : SentPinRepository {
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
