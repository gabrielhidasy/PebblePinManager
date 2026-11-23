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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import xyz.hidasy.pebblepinmanager.data.SentPinRecord
import xyz.hidasy.pebblepinmanager.data.SentPinRepository
import xyz.hidasy.pebblepinmanager.domain.EventSource
import xyz.hidasy.pebblepinmanager.domain.PinAccentColor
import xyz.hidasy.pebblepinmanager.domain.PinIcon
import xyz.hidasy.pebblepinmanager.domain.PinLayoutType
import xyz.hidasy.pebblepinmanager.domain.TimelineEvent
import xyz.hidasy.pebblepinmanager.pebble.ConnectedPebbleWatch
import xyz.hidasy.pebblepinmanager.pebble.PebbleCompanionStatus
import xyz.hidasy.pebblepinmanager.pebble.PebbleHardwarePlatform
import xyz.hidasy.pebblepinmanager.pebble.PinDeleteResult
import xyz.hidasy.pebblepinmanager.pebble.PinSendResult
import xyz.hidasy.pebblepinmanager.pebble.TimelinePinSender
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class PinComposerViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val fixedClock = Clock.fixed(
        Instant.parse("2026-11-21T12:00:00Z"),
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
    fun `blank title prevents sending and shows inline validation error`() = runTest {
        val sender = StubTimelinePinSender()
        val repo = StubSentPinRepository()
        val vm = PinComposerViewModel(sender, repo, fixedClock) { "ppm-fixed" }
        advanceUntilIdle()

        vm.sendPin()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertNotNull(state.titleError)
        assertTrue(state.feedback is SendFeedback.Error)
        assertTrue(sender.sentEvents.isEmpty())
    }

    @Test
    fun `successful send clears inputs, saves history, and displays connected Pebble Time 2`() = runTest {
        val emeryWatch = ConnectedPebbleWatch(
            id = "w-emery",
            name = "My Pebble Time 2",
            platformCode = "emery",
            platform = PebbleHardwarePlatform.EMERY,
            revision = "robert",
            firmwareVersion = "4.9.0",
        )
        val sender = StubTimelinePinSender(
            sendResult = PinSendResult.Success("ppm-fixed"),
            watches = listOf(emeryWatch),
        )
        val repo = StubSentPinRepository()
        val vm = PinComposerViewModel(sender, repo, fixedClock) { "ppm-fixed" }
        advanceUntilIdle()

        vm.onTitleChange("Doctor Visit")
        vm.onDescriptionChange("Bring insurance card")
        vm.onDateSelected(LocalDate.of(2026, 11, 21))
        vm.onTimeSelected(LocalTime.of(15, 30))
        vm.sendPin()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("", state.title)
        assertEquals("", state.description)
        assertTrue(state.feedback is SendFeedback.Success)
        assertTrue((state.feedback as SendFeedback.Success).details.contains("Pebble Time 2 (emery)"))
        assertEquals(1, state.sentPins.size)
        assertEquals("Doctor Visit", state.sentPins.first().title)
    }

    @Test
    fun `missing Pebble app surfaces clear error feedback without clearing user inputs`() = runTest {
        val sender = StubTimelinePinSender(
            sendResult = PinSendResult.NoPebbleApp,
            companionStatus = PebbleCompanionStatus.Unavailable("No Pebble app installed"),
        )
        val repo = StubSentPinRepository()
        val vm = PinComposerViewModel(sender, repo, fixedClock) { "ppm-fixed" }
        advanceUntilIdle()

        vm.onTitleChange("Catch train")
        vm.sendPin()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("Catch train", state.title)
        assertTrue(state.feedback is SendFeedback.Error)
        assertTrue((state.feedback as SendFeedback.Error).headline.contains("not available"))
        assertTrue(state.sentPins.isEmpty())
    }

    @Test
    fun `quick offset adds to current payload time and rolls over dates`() = runTest {
        val vm = PinComposerViewModel(StubTimelinePinSender(), StubSentPinRepository(), fixedClock)
        advanceUntilIdle()

        // Initial state is fixedClock (12:00) + default 15 min = 12:15
        assertEquals(LocalDate.of(2026, 11, 21), vm.uiState.value.selectedDate)
        assertEquals(LocalTime.of(12, 15), vm.uiState.value.selectedTime)

        vm.applyQuickOffsetMinutes(15)
        assertEquals(LocalDate.of(2026, 11, 21), vm.uiState.value.selectedDate)
        assertEquals(LocalTime.of(12, 30), vm.uiState.value.selectedTime)
        assertEquals("2026-11-21T12:30:00Z", vm.uiState.value.selectedUtcIso8601)

        vm.applyQuickOffsetMinutes(60)
        assertEquals(LocalDate.of(2026, 11, 21), vm.uiState.value.selectedDate)
        assertEquals(LocalTime.of(13, 30), vm.uiState.value.selectedTime)
        assertEquals("2026-11-21T13:30:00Z", vm.uiState.value.selectedUtcIso8601)

        vm.applyTomorrowMorning(9, 0)
        assertEquals(LocalDate.of(2026, 11, 22), vm.uiState.value.selectedDate)
        assertEquals(LocalTime.of(9, 0), vm.uiState.value.selectedTime)

        vm.applyQuickOffsetMinutes(60)
        assertEquals(LocalDate.of(2026, 11, 22), vm.uiState.value.selectedDate)
        assertEquals(LocalTime.of(10, 0), vm.uiState.value.selectedTime)

        // Midnight rollover
        vm.onDateSelected(LocalDate.of(2026, 11, 21))
        vm.onTimeSelected(LocalTime.of(23, 50))
        vm.applyQuickOffsetMinutes(15)
        assertEquals(LocalDate.of(2026, 11, 22), vm.uiState.value.selectedDate)
        assertEquals(LocalTime.of(0, 5), vm.uiState.value.selectedTime)
    }

    @Test
    fun `applyNow sets payload time to current clock truncated to minutes and clears time error`() = runTest {
        val clockWithSeconds = Clock.fixed(
            Instant.parse("2026-11-21T14:23:47Z"),
            ZoneId.of("America/New_York"),
        )
        val vm = PinComposerViewModel(StubTimelinePinSender(), StubSentPinRepository(), clockWithSeconds)
        advanceUntilIdle()

        // Force a time validation error by selecting a date far in the past
        vm.onTitleChange("Past Event")
        vm.onDateSelected(LocalDate.of(2026, 11, 1))
        vm.onTimeSelected(LocalTime.of(10, 0))
        vm.sendPin()
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.timeError)
        assertNotNull(vm.uiState.value.feedback)

        // applyNow should reset to 09:23 EST (14:23:00Z) and clear timeError + feedback
        vm.applyNow()
        assertEquals(LocalDate.of(2026, 11, 21), vm.uiState.value.selectedDate)
        assertEquals(LocalTime.of(9, 23), vm.uiState.value.selectedTime)
        assertEquals("2026-11-21T14:23:00Z", vm.uiState.value.selectedUtcIso8601)
        assertTrue(vm.uiState.value.formattedLocalPayloadTime.contains("09:23"))
        assertTrue(vm.uiState.value.formattedLocalPayloadTime.contains("UTC-05:00"))
        assertNull(vm.uiState.value.timeError)
        assertNull(vm.uiState.value.feedback)

        // Subsequent +15 min and +1h advance from the newly set payload time
        vm.applyQuickOffsetMinutes(15)
        assertEquals(LocalTime.of(9, 38), vm.uiState.value.selectedTime)
        assertEquals("2026-11-21T14:38:00Z", vm.uiState.value.selectedUtcIso8601)
        assertTrue(vm.uiState.value.formattedLocalPayloadTime.contains("09:38"))

        vm.applyQuickOffsetMinutes(60)
        assertEquals(LocalTime.of(10, 38), vm.uiState.value.selectedTime)
        assertEquals("2026-11-21T15:38:00Z", vm.uiState.value.selectedUtcIso8601)
        assertTrue(vm.uiState.value.formattedLocalPayloadTime.contains("10:38"))
    }

    @Test
    fun `custom icon, duration, and accent color are transmitted, persisted, and reset after send`() = runTest {
        val basaltWatch = ConnectedPebbleWatch(
            id = "w-basalt",
            name = "Pebble Time",
            platformCode = "basalt",
            platform = PebbleHardwarePlatform.BASALT,
            revision = "snowy",
            firmwareVersion = "4.3.0",
        )
        val sender = StubTimelinePinSender(watches = listOf(basaltWatch))
        val repo = StubSentPinRepository()
        val vm = PinComposerViewModel(sender, repo, fixedClock) { "ppm-custom" }
        advanceUntilIdle()

        assertTrue(vm.uiState.value.supportsColorBackground)

        vm.onTitleChange("Soccer Match")
        vm.onIconSelected(PinIcon.TIMELINE_SPORTS)
        vm.onDurationInputChange("90")
        vm.onBackgroundColorSelected(PinAccentColor.GREEN)
        vm.sendPin()
        advanceUntilIdle()

        assertEquals(1, sender.sentEvents.size)
        val sent = sender.sentEvents.single()
        assertEquals("system://images/TIMELINE_SPORTS", sent.iconUri)
        assertEquals(90, sent.durationMinutes)
        assertEquals("#00AA00", sent.backgroundColor)

        val persisted = vm.uiState.value.sentPins.single()
        assertEquals("system://images/TIMELINE_SPORTS", persisted.iconUri)
        assertEquals(90, persisted.durationMinutes)
        assertEquals("#00AA00", persisted.backgroundColor)

        val state = vm.uiState.value
        assertEquals(PinIcon.DEFAULT, state.selectedIcon)
        assertEquals("", state.durationInput)
        assertNull(state.selectedBackgroundColor)
        assertTrue((state.feedback as SendFeedback.Success).details.contains("1h 30m"))
    }

    @Test
    fun `default pin sends instant duration, calendar icon, and null background color`() = runTest {
        val sender = StubTimelinePinSender()
        val repo = StubSentPinRepository()
        val vm = PinComposerViewModel(sender, repo, fixedClock) { "ppm-default" }
        advanceUntilIdle()

        vm.onTitleChange("Quick Reminder")
        vm.sendPin()
        advanceUntilIdle()

        val sent = sender.sentEvents.single()
        assertEquals(PinIcon.TIMELINE_CALENDAR.uri, sent.iconUri)
        assertNull(sent.durationMinutes)
        assertNull(sent.backgroundColor)
    }

    @Test
    fun `invalid duration input prevents sending and surfaces inline duration error`() = runTest {
        val sender = StubTimelinePinSender()
        val repo = StubSentPinRepository()
        val vm = PinComposerViewModel(sender, repo, fixedClock) { "ppm-bad-dur" }
        advanceUntilIdle()

        vm.onTitleChange("Workshop")
        vm.onDurationInputChange("abc")
        vm.sendPin()
        advanceUntilIdle()

        assertTrue(sender.sentEvents.isEmpty())
        assertNotNull(vm.uiState.value.durationError)
        assertEquals("Workshop", vm.uiState.value.title)

        vm.onDurationInputChange("2000")
        assertNull(vm.uiState.value.durationError)
        vm.sendPin()
        advanceUntilIdle()

        assertTrue(sender.sentEvents.isEmpty())
        assertNotNull(vm.uiState.value.durationError)

        vm.onDurationPresetSelected(30)
        assertEquals("30", vm.uiState.value.durationInput)
        assertEquals(30, vm.uiState.value.parsedDurationMinutes)
        vm.onDurationPresetSelected(null)
        assertEquals("", vm.uiState.value.durationInput)
        assertNull(vm.uiState.value.parsedDurationMinutes)
    }

    @Test
    fun `monochrome watch hides color background support and omits backgroundColor on send`() = runTest {
        val dioriteWatch = ConnectedPebbleWatch(
            id = "w-diorite",
            name = "Pebble 2",
            platformCode = "diorite",
            platform = PebbleHardwarePlatform.DIORITE,
            revision = "silk",
            firmwareVersion = "4.3.0",
        )
        val sender = StubTimelinePinSender(watches = listOf(dioriteWatch))
        val repo = StubSentPinRepository()
        val vm = PinComposerViewModel(sender, repo, fixedClock) { "ppm-mono" }
        advanceUntilIdle()

        assertFalse(vm.uiState.value.supportsColorBackground)

        vm.onTitleChange("Morning Run")
        vm.onIconSelected(PinIcon.NOTIFICATION_FLAG)
        vm.onBackgroundColorSelected(PinAccentColor.ORANGE)
        vm.sendPin()
        advanceUntilIdle()

        val sent = sender.sentEvents.single()
        assertEquals(PinIcon.NOTIFICATION_FLAG.uri, sent.iconUri)
        assertNull(sent.backgroundColor)
    }

    @Test
    fun `color background is enabled for chalk and gabbro watches and when offline`() = runTest {
        val chalkWatch = ConnectedPebbleWatch(
            id = "w-chalk",
            name = "Pebble Time Round",
            platformCode = "chalk",
            platform = PebbleHardwarePlatform.CHALK,
            revision = "spalding",
            firmwareVersion = "4.3.0",
        )
        val gabbroWatch = ConnectedPebbleWatch(
            id = "w-gabbro",
            name = "Pebble Time 2 Round",
            platformCode = "gabbro",
            platform = PebbleHardwarePlatform.GABBRO,
            revision = "gabbro",
            firmwareVersion = "4.9.0",
        )

        val chalkVm = PinComposerViewModel(
            StubTimelinePinSender(watches = listOf(chalkWatch)),
            StubSentPinRepository(),
            fixedClock,
        )
        val gabbroVm = PinComposerViewModel(
            StubTimelinePinSender(watches = listOf(gabbroWatch)),
            StubSentPinRepository(),
            fixedClock,
        )
        val offlineVm = PinComposerViewModel(
            StubTimelinePinSender(watches = emptyList()),
            StubSentPinRepository(),
            fixedClock,
        )
        advanceUntilIdle()

        assertTrue(chalkVm.uiState.value.supportsColorBackground)
        assertTrue(gabbroVm.uiState.value.supportsColorBackground)
        assertTrue(offlineVm.uiState.value.supportsColorBackground)
    }

    @Test
    fun `startEditingPin prefills form and sendPin updates existing pin ID in place`() = runTest {
        val emeryWatch = ConnectedPebbleWatch(
            id = "w-emery",
            name = "My Pebble Time 2",
            platformCode = "emery",
            platform = PebbleHardwarePlatform.EMERY,
            revision = "robert",
            firmwareVersion = "4.9.0",
        )
        val existingRecord = SentPinRecord(
            id = "ppm-existing-1",
            title = "Dentist",
            description = "Room 101",
            startTimeMillis = Instant.parse("2026-11-21T15:00:00Z").toEpochMilli(),
            durationMinutes = 30,
            iconUri = PinIcon.GENERIC_CONFIRMATION.uri,
            backgroundColor = PinAccentColor.ORANGE.hexCode,
            layoutType = PinLayoutType.CALENDAR_PIN.name,
            source = EventSource.MANUAL.name,
            sentAtMillis = Instant.parse("2026-11-21T11:00:00Z").toEpochMilli(),
        )
        var generatedIdCount = 0
        val sender = StubTimelinePinSender(watches = listOf(emeryWatch))
        val repo = StubSentPinRepository(initialPins = listOf(existingRecord))
        val vm = PinComposerViewModel(sender, repo, fixedClock) {
            generatedIdCount += 1
            "ppm-new-$generatedIdCount"
        }
        advanceUntilIdle()

        vm.startEditingPin(existingRecord)

        val editingState = vm.uiState.value
        assertTrue(editingState.isEditing)
        assertEquals("ppm-existing-1", editingState.editingPinId)
        assertEquals("Dentist", editingState.title)
        assertEquals("Room 101", editingState.description)
        assertEquals(LocalDate.of(2026, 11, 21), editingState.selectedDate)
        assertEquals(LocalTime.of(15, 0), editingState.selectedTime)
        assertEquals(PinIcon.GENERIC_CONFIRMATION, editingState.selectedIcon)
        assertEquals("30", editingState.durationInput)
        assertEquals(PinAccentColor.ORANGE, editingState.selectedBackgroundColor)

        vm.onTitleChange("Dentist (Rescheduled)")
        vm.onDescriptionChange("Room 204")
        vm.onTimeSelected(LocalTime.of(16, 45))
        vm.onIconSelected(PinIcon.ALARM_CLOCK)
        vm.onDurationInputChange("45")
        vm.onBackgroundColorSelected(PinAccentColor.TEAL)
        vm.sendPin()
        advanceUntilIdle()

        assertEquals(0, generatedIdCount)
        assertEquals(1, sender.sentEvents.size)
        val sent = sender.sentEvents.single()
        assertEquals("ppm-existing-1", sent.id)
        assertEquals("Dentist (Rescheduled)", sent.title)
        assertEquals("Room 204", sent.description)
        assertEquals(Instant.parse("2026-11-21T16:45:00Z").toEpochMilli(), sent.startTimeMillis)
        assertEquals(45, sent.durationMinutes)
        assertEquals(PinIcon.ALARM_CLOCK.uri, sent.iconUri)
        assertEquals(PinAccentColor.TEAL.hexCode, sent.backgroundColor)
        assertEquals(PinLayoutType.CALENDAR_PIN, sent.layoutType)

        val finalState = vm.uiState.value
        assertFalse(finalState.isEditing)
        assertNull(finalState.editingPinId)
        assertEquals("", finalState.title)
        assertEquals("", finalState.description)
        assertEquals(PinIcon.DEFAULT, finalState.selectedIcon)
        assertEquals("", finalState.durationInput)
        assertNull(finalState.selectedBackgroundColor)
        assertEquals(1, finalState.sentPins.size)
        val persisted = finalState.sentPins.single()
        assertEquals("ppm-existing-1", persisted.id)
        assertEquals("Dentist (Rescheduled)", persisted.title)
        assertEquals(45, persisted.durationMinutes)
        assertEquals(PinIcon.ALARM_CLOCK.uri, persisted.iconUri)
        assertEquals(PinAccentColor.TEAL.hexCode, persisted.backgroundColor)
        assertTrue(finalState.feedback is SendFeedback.Success)
        assertEquals("Pin updated on Pebble Timeline", (finalState.feedback as SendFeedback.Success).headline)
        assertTrue((finalState.feedback as SendFeedback.Success).details.contains("ppm-existing-1"))
        assertTrue((finalState.feedback as SendFeedback.Success).details.contains("45 min"))
    }

    @Test
    fun `cancelEditing exits edit mode and resets form fields`() = runTest {
        val existingRecord = SentPinRecord(
            id = "ppm-edit-cancel",
            title = "Standup",
            description = "Zoom link",
            startTimeMillis = Instant.parse("2026-11-21T14:00:00Z").toEpochMilli(),
            durationMinutes = 15,
            iconUri = PinIcon.NOTIFICATION_REMINDER.uri,
            backgroundColor = PinAccentColor.COBALT.hexCode,
            sentAtMillis = Instant.parse("2026-11-21T11:00:00Z").toEpochMilli(),
        )
        val vm = PinComposerViewModel(
            StubTimelinePinSender(),
            StubSentPinRepository(initialPins = listOf(existingRecord)),
            fixedClock,
        )
        advanceUntilIdle()

        vm.startEditingPin(existingRecord)
        assertTrue(vm.uiState.value.isEditing)
        assertEquals(PinIcon.NOTIFICATION_REMINDER, vm.uiState.value.selectedIcon)
        assertEquals("15", vm.uiState.value.durationInput)
        assertEquals(PinAccentColor.COBALT, vm.uiState.value.selectedBackgroundColor)

        vm.cancelEditing()

        val state = vm.uiState.value
        assertFalse(state.isEditing)
        assertNull(state.editingPinId)
        assertEquals("", state.title)
        assertEquals("", state.description)
        assertEquals(PinIcon.DEFAULT, state.selectedIcon)
        assertEquals("", state.durationInput)
        assertNull(state.selectedBackgroundColor)
        assertEquals(LocalDate.of(2026, 11, 21), state.selectedDate)
        assertEquals(LocalTime.of(12, 15), state.selectedTime)
    }

    @Test
    fun `failed update preserves edit mode and edited form fields for retry`() = runTest {
        val existingRecord = SentPinRecord(
            id = "ppm-retry-1",
            title = "Flight",
            description = "Gate A1",
            startTimeMillis = Instant.parse("2026-11-21T18:00:00Z").toEpochMilli(),
            sentAtMillis = Instant.parse("2026-11-21T11:00:00Z").toEpochMilli(),
        )
        val sender = StubTimelinePinSender(
            sendResult = PinSendResult.UnexpectedError("Companion IPC timeout"),
        )
        val repo = StubSentPinRepository(initialPins = listOf(existingRecord))
        val vm = PinComposerViewModel(sender, repo, fixedClock)
        advanceUntilIdle()

        vm.startEditingPin(existingRecord)
        vm.onTitleChange("Flight (Delayed)")
        vm.sendPin()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.isEditing)
        assertEquals("ppm-retry-1", state.editingPinId)
        assertEquals("Flight (Delayed)", state.title)
        assertTrue(state.feedback is SendFeedback.Error)
        assertEquals("Failed to update timeline pin", (state.feedback as SendFeedback.Error).headline)
        assertEquals("Flight", state.sentPins.single().title)
    }

    @Test
    fun `deletePin removes pin via Pebble sender and local repository`() = runTest {
        val record = SentPinRecord(
            id = "ppm-del-1",
            title = "Lunch with Sam",
            startTimeMillis = Instant.parse("2026-11-21T13:00:00Z").toEpochMilli(),
            sentAtMillis = Instant.parse("2026-11-21T11:00:00Z").toEpochMilli(),
        )
        val sender = StubTimelinePinSender()
        val repo = StubSentPinRepository(initialPins = listOf(record))
        val vm = PinComposerViewModel(sender, repo, fixedClock)
        advanceUntilIdle()

        vm.deletePin("ppm-del-1")
        advanceUntilIdle()

        assertEquals(listOf("ppm-del-1"), sender.deletedPinIds)
        val state = vm.uiState.value
        assertTrue(state.sentPins.isEmpty())
        assertNull(state.deletingPinId)
        assertTrue(state.feedback is SendFeedback.Success)
        assertEquals("Pin deleted from Pebble Timeline", (state.feedback as SendFeedback.Success).headline)
        assertTrue((state.feedback as SendFeedback.Success).details.contains("Lunch with Sam"))
    }

    @Test
    fun `deletePin clears edit mode when deleting the currently edited pin`() = runTest {
        val record = SentPinRecord(
            id = "ppm-editing-del",
            title = "Team Sync",
            description = "Conference room B",
            startTimeMillis = Instant.parse("2026-11-21T14:00:00Z").toEpochMilli(),
            durationMinutes = 60,
            iconUri = PinIcon.LOCATION.uri,
            backgroundColor = PinAccentColor.PURPLE.hexCode,
            sentAtMillis = Instant.parse("2026-11-21T11:00:00Z").toEpochMilli(),
        )
        val sender = StubTimelinePinSender()
        val repo = StubSentPinRepository(initialPins = listOf(record))
        val vm = PinComposerViewModel(sender, repo, fixedClock)
        advanceUntilIdle()

        vm.startEditingPin(record)
        assertTrue(vm.uiState.value.isEditing)

        vm.deletePin("ppm-editing-del")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isEditing)
        assertNull(state.editingPinId)
        assertEquals("", state.title)
        assertEquals("", state.description)
        assertEquals(PinIcon.DEFAULT, state.selectedIcon)
        assertEquals("", state.durationInput)
        assertNull(state.selectedBackgroundColor)
        assertTrue(state.sentPins.isEmpty())
    }

    @Test
    fun `deletePin removes stale local pin when Pebble reports UnknownPin`() = runTest {
        val record = SentPinRecord(
            id = "ppm-stale-1",
            title = "Expired Event",
            startTimeMillis = Instant.parse("2026-11-21T13:00:00Z").toEpochMilli(),
            sentAtMillis = Instant.parse("2026-11-21T11:00:00Z").toEpochMilli(),
        )
        val sender = StubTimelinePinSender(
            deleteResult = PinDeleteResult.UnknownPin("ppm-stale-1"),
        )
        val repo = StubSentPinRepository(initialPins = listOf(record))
        val vm = PinComposerViewModel(sender, repo, fixedClock)
        advanceUntilIdle()

        vm.deletePin("ppm-stale-1")
        advanceUntilIdle()

        assertEquals(listOf("ppm-stale-1"), sender.deletedPinIds)
        val state = vm.uiState.value
        assertTrue(state.sentPins.isEmpty())
        assertTrue(state.feedback is SendFeedback.Success)
        assertEquals("Pin removed from history", (state.feedback as SendFeedback.Success).headline)
    }

    @Test
    fun `deletePin keeps local history intact and shows error when companion app fails`() = runTest {
        val record = SentPinRecord(
            id = "ppm-keep-1",
            title = "Important Meeting",
            startTimeMillis = Instant.parse("2026-11-21T13:00:00Z").toEpochMilli(),
            sentAtMillis = Instant.parse("2026-11-21T11:00:00Z").toEpochMilli(),
        )
        val failureCases = listOf(
            PinDeleteResult.NoPebbleApp to "not available",
            PinDeleteResult.NoPermissions("e4b7c3a1-8f2d-4c6e-9a51-7b3d2e8f1042") to "permission denied",
            PinDeleteResult.UnsupportedAction to "unsupported",
            PinDeleteResult.UnexpectedError("Binder died") to "Failed to delete",
        )

        failureCases.forEach { (deleteResult, expectedHeadlineFragment) ->
            val sender = StubTimelinePinSender(deleteResult = deleteResult)
            val repo = StubSentPinRepository(initialPins = listOf(record))
            val vm = PinComposerViewModel(sender, repo, fixedClock)
            advanceUntilIdle()

            vm.deletePin("ppm-keep-1")
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(1, state.sentPins.size)
            assertNull(state.deletingPinId)
            assertTrue(state.feedback is SendFeedback.Error)
            assertTrue(
                "Expected '${(state.feedback as SendFeedback.Error).headline}' to contain '$expectedHeadlineFragment'",
                (state.feedback as SendFeedback.Error).headline.contains(expectedHeadlineFragment, ignoreCase = true),
            )
        }
    }

    @Test
    fun `successful send and delete trigger optional onPinsChanged watch sync callback`() = runTest {
        var syncTriggered = 0
        val sender = StubTimelinePinSender()
        val repo = StubSentPinRepository()
        val vm = PinComposerViewModel(
            pinSender = sender,
            repository = repo,
            clock = fixedClock,
            onPinsChanged = { syncTriggered++ },
            pinIdGenerator = { "ppm-sync" },
        )
        advanceUntilIdle()

        vm.onTitleChange("Sync Watch Test")
        vm.sendPin()
        advanceUntilIdle()
        assertEquals(1, syncTriggered)

        vm.deletePin("ppm-sync")
        advanceUntilIdle()
        assertEquals(2, syncTriggered)
    }

    @Test
    fun `recent sent pins exclude notification auto-pins while keeping manual pins`() = runTest {
        val manualPin = SentPinRecord(
            id = "ppm-manual-1",
            title = "Dentist",
            startTimeMillis = Instant.parse("2026-11-21T15:00:00Z").toEpochMilli(),
            source = EventSource.MANUAL.name,
            sentAtMillis = Instant.parse("2026-11-21T11:00:00Z").toEpochMilli(),
        )
        val autoPin = SentPinRecord(
            id = "ppm-notif-1",
            title = "Flight LH123",
            description = "Gate B12",
            startTimeMillis = Instant.parse("2026-11-21T18:00:00Z").toEpochMilli(),
            source = EventSource.NOTIFICATION.name,
            sourceKey = "0|com.example.flights|42|null|1000",
            sentAtMillis = Instant.parse("2026-11-21T11:30:00Z").toEpochMilli(),
        )
        val repo = StubSentPinRepository(initialPins = listOf(autoPin, manualPin))
        val vm = PinComposerViewModel(StubTimelinePinSender(), repo, fixedClock)
        advanceUntilIdle()

        assertEquals(listOf("ppm-manual-1"), vm.uiState.value.sentPins.map { it.id })
        assertFalse(vm.uiState.value.sentPins.any { it.isAutoPin })
    }

    @Test
    fun `editing an auto-pin in composer updates in place and preserves notification source`() = runTest {
        val autoPin = SentPinRecord(
            id = "ppm-notif-edit-1",
            title = "Flight LH123",
            description = "Gate B12",
            startTimeMillis = Instant.parse("2026-11-21T18:00:00Z").toEpochMilli(),
            iconUri = PinIcon.SCHEDULED_FLIGHT.uri,
            backgroundColor = PinAccentColor.SKY.hexCode,
            source = EventSource.NOTIFICATION.name,
            sourceKey = "0|com.example.flights|42|null|1000",
            sentAtMillis = Instant.parse("2026-11-21T11:30:00Z").toEpochMilli(),
        )
        val sender = StubTimelinePinSender()
        val repo = StubSentPinRepository(initialPins = listOf(autoPin))
        val vm = PinComposerViewModel(sender, repo, fixedClock)
        advanceUntilIdle()

        // Excluded from manual Recent Sent Pins before edit
        assertTrue(vm.uiState.value.sentPins.isEmpty())

        // Loaded into composer from Auto-Pins tab
        vm.startEditingPin(autoPin)
        assertTrue(vm.uiState.value.isEditing)
        assertEquals("ppm-notif-edit-1", vm.uiState.value.editingPinId)
        assertTrue(vm.uiState.value.editingPin?.isAutoPin == true)

        vm.onTitleChange("Flight LH123 (Boarding)")
        vm.onDescriptionChange("Gate C04")
        vm.sendPin()
        advanceUntilIdle()

        val sent = sender.sentEvents.single()
        assertEquals("ppm-notif-edit-1", sent.id)
        assertEquals("Flight LH123 (Boarding)", sent.title)
        assertEquals("Gate C04", sent.description)
        assertEquals(EventSource.NOTIFICATION, sent.source)
        assertEquals("0|com.example.flights|42|null|1000", sent.sourceKey)

        // Remains persisted as NOTIFICATION and stays excluded from manual Recent Sent Pins
        val stored = repo.getPinById("ppm-notif-edit-1")
        assertNotNull(stored)
        assertEquals(EventSource.NOTIFICATION.name, stored?.source)
        assertEquals("0|com.example.flights|42|null|1000", stored?.sourceKey)
        assertEquals("Flight LH123 (Boarding)", stored?.title)
        assertTrue(vm.uiState.value.sentPins.isEmpty())
        assertFalse(vm.uiState.value.isEditing)
    }

    @Test
    fun `deleting currently edited auto-pin externally clears composer edit mode`() = runTest {
        val autoPin = SentPinRecord(
            id = "ppm-notif-ext-del",
            title = "Delivery Arriving",
            startTimeMillis = Instant.parse("2026-11-21T16:00:00Z").toEpochMilli(),
            source = EventSource.NOTIFICATION.name,
            sourceKey = "0|com.example.delivery|7|null|1000",
            sentAtMillis = Instant.parse("2026-11-21T11:30:00Z").toEpochMilli(),
        )
        val repo = StubSentPinRepository(initialPins = listOf(autoPin))
        val vm = PinComposerViewModel(StubTimelinePinSender(), repo, fixedClock)
        advanceUntilIdle()

        vm.startEditingPin(autoPin)
        assertTrue(vm.uiState.value.isEditing)

        repo.deletePinRecord("ppm-notif-ext-del")
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isEditing)
        assertNull(vm.uiState.value.editingPinId)
        assertEquals("", vm.uiState.value.title)
    }
}

private class StubTimelinePinSender(
    private val sendResult: PinSendResult? = null,
    private val deleteResult: PinDeleteResult? = null,
    private val companionStatus: PebbleCompanionStatus = PebbleCompanionStatus.Available(
        "coredevices.coreapp",
        listOf("coredevices.coreapp"),
    ),
    watches: List<ConnectedPebbleWatch> = emptyList(),
) : TimelinePinSender {
    val sentEvents = mutableListOf<TimelineEvent>()
    val deletedPinIds = mutableListOf<String>()
    private val watchesFlow = MutableStateFlow(watches)

    override suspend fun sendPin(event: TimelineEvent): PinSendResult {
        sentEvents += event
        return sendResult ?: PinSendResult.Success(event.id)
    }

    override suspend fun deletePin(pinId: String): PinDeleteResult {
        deletedPinIds += pinId
        return deleteResult ?: PinDeleteResult.Success(pinId)
    }

    override suspend fun getCompanionStatus(): PebbleCompanionStatus = companionStatus
    override fun observeConnectedWatches(): Flow<List<ConnectedPebbleWatch>> = watchesFlow
}

private class StubSentPinRepository(
    initialPins: List<SentPinRecord> = emptyList(),
) : SentPinRepository {
    private val pins = MutableStateFlow(initialPins)
    override fun observeSentPins(): Flow<List<SentPinRecord>> = pins
    override suspend fun saveSentPin(record: SentPinRecord) {
        pins.value = listOf(record) + pins.value.filterNot { it.id == record.id }
    }
    override suspend fun getPinById(id: String): SentPinRecord? = pins.value.firstOrNull { it.id == id }
    override suspend fun getPinBySourceKey(sourceKey: String): SentPinRecord? =
        pins.value.firstOrNull { it.sourceKey == sourceKey }
    override suspend fun deletePinRecord(id: String) {
        pins.value = pins.value.filterNot { it.id == id }
    }
    override suspend fun clearAll() {
        pins.value = emptyList()
    }
}
