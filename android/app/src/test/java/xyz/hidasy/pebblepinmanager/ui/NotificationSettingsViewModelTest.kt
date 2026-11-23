package xyz.hidasy.pebblepinmanager.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
import xyz.hidasy.pebblepinmanager.domain.TimelineEvent
import xyz.hidasy.pebblepinmanager.notifications.AppNotificationRuleConfig
import xyz.hidasy.pebblepinmanager.notifications.InstalledAppInfo
import xyz.hidasy.pebblepinmanager.notifications.InstalledAppsProvider
import xyz.hidasy.pebblepinmanager.notifications.NotificationAccessStatusChecker
import xyz.hidasy.pebblepinmanager.notifications.NotificationRegexRule
import xyz.hidasy.pebblepinmanager.notifications.NotificationRuleAction
import xyz.hidasy.pebblepinmanager.notifications.NotificationRuleSettings
import xyz.hidasy.pebblepinmanager.notifications.NotificationSettingsRepository
import xyz.hidasy.pebblepinmanager.notifications.NotificationTextField
import xyz.hidasy.pebblepinmanager.notifications.RuleMatchScope
import xyz.hidasy.pebblepinmanager.pebble.ConnectedPebbleWatch
import xyz.hidasy.pebblepinmanager.pebble.PebbleCompanionStatus
import xyz.hidasy.pebblepinmanager.pebble.PinDeleteResult
import xyz.hidasy.pebblepinmanager.pebble.PinSendResult
import xyz.hidasy.pebblepinmanager.pebble.TimelinePinSender
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationSettingsViewModelTest {
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
    fun `refreshes permission status, toggles allowlisted apps, and supports search and selected-only filter`() = runTest {
        var permissionGranted = false
        val settingsRepo = FakeNotificationSettingsRepository()
        val appsProvider = FakeInstalledAppsProvider(
            listOf(
                InstalledAppInfo("com.example.calendar", "Calendar"),
                InstalledAppInfo("com.example.delivery", "Parcel Tracker"),
                InstalledAppInfo("com.example.flights", "Skyline Flights"),
            ),
        )
        val listenerFlow = MutableStateFlow(false)
        val sentPinsRepo = FakeNotifSentPinRepository(
            listOf(
                SentPinRecord(
                    id = "ppm-notif-1",
                    title = "Flight LH123",
                    startTimeMillis = 1_700_000_000_000L,
                    source = EventSource.NOTIFICATION.name,
                    sentAtMillis = 1_700_000_000_000L,
                ),
                SentPinRecord(
                    id = "ppm-manual-1",
                    title = "Dentist",
                    startTimeMillis = 1_700_000_000_000L,
                    source = EventSource.MANUAL.name,
                    sentAtMillis = 1_700_000_000_000L,
                ),
            ),
        )

        val vm = NotificationSettingsViewModel(
            settingsRepository = settingsRepo,
            accessStatusChecker = object : NotificationAccessStatusChecker {
                override fun isNotificationAccessEnabled(): Boolean = permissionGranted
            },
            installedAppsProvider = appsProvider,
            sentPinRepository = sentPinsRepo,
            pinSender = FakeNotifTimelinePinSender(),
            listenerConnectedFlow = listenerFlow,
            clock = fixedClock,
        )
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isNotificationAccessEnabled)
        assertEquals(3, vm.uiState.value.installedApps.size)
        assertEquals(1, vm.uiState.value.autoPinCount)
        assertEquals(listOf("ppm-notif-1"), vm.uiState.value.autoPins.map { it.id })

        permissionGranted = true
        listenerFlow.value = true
        vm.refreshPermissionStatus()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isNotificationAccessEnabled)
        assertTrue(vm.uiState.value.isListenerConnected)

        vm.onTogglePackageAllowed("com.example.flights")
        advanceUntilIdle()
        assertEquals(setOf("com.example.flights"), vm.uiState.value.settings.allowedPackages)
        // Selected app sorts to the top
        assertEquals("com.example.flights", vm.uiState.value.filteredInstalledApps.first().packageName)

        vm.onShowSelectedAppsOnlyChange(true)
        assertEquals(listOf("com.example.flights"), vm.uiState.value.filteredInstalledApps.map { it.packageName })

        vm.onShowSelectedAppsOnlyChange(false)
        vm.onAppSearchQueryChange("parcel")
        assertEquals(listOf("com.example.delivery"), vm.uiState.value.filteredInstalledApps.map { it.packageName })
    }

    @Test
    fun `validates manual package names and tracks unlisted allowlisted packages`() = runTest {
        val settingsRepo = FakeNotificationSettingsRepository()
        val vm = NotificationSettingsViewModel(
            settingsRepository = settingsRepo,
            accessStatusChecker = object : NotificationAccessStatusChecker {
                override fun isNotificationAccessEnabled(): Boolean = true
            },
            installedAppsProvider = FakeInstalledAppsProvider(emptyList()),
            sentPinRepository = FakeNotifSentPinRepository(),
            pinSender = FakeNotifTimelinePinSender(),
            clock = fixedClock,
        )
        advanceUntilIdle()

        vm.onManualPackageInputChange("not a package")
        vm.onAddManualPackage()
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.manualPackageError)
        assertTrue(vm.uiState.value.settings.allowedPackages.isEmpty())

        vm.onManualPackageInputChange("com.android.calendar")
        vm.onAddManualPackage()
        advanceUntilIdle()
        assertNull(vm.uiState.value.manualPackageError)
        assertEquals("", vm.uiState.value.manualPackageInput)
        assertEquals(listOf("com.android.calendar"), vm.uiState.value.unlistedAllowedPackages)
    }

    @Test
    fun `blocks saving invalid regexes and persists valid keyword, regex, and appearance rules`() = runTest {
        val settingsRepo = FakeNotificationSettingsRepository()
        val vm = NotificationSettingsViewModel(
            settingsRepository = settingsRepo,
            accessStatusChecker = object : NotificationAccessStatusChecker {
                override fun isNotificationAccessEnabled(): Boolean = true
            },
            installedAppsProvider = FakeInstalledAppsProvider(emptyList()),
            sentPinRepository = FakeNotifSentPinRepository(),
            pinSender = FakeNotifTimelinePinSender(),
            clock = fixedClock,
        )
        advanceUntilIdle()

        vm.onKeywordFilterInputChange("flight, gate")
        vm.onTitleRegexInputChange("[unclosed")
        vm.onSaveParsingRules()
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.titleRegexError)
        assertEquals("", settingsRepo.getSettings().keywordFilter)
        assertNull(vm.uiState.value.rulesSavedMessage)

        vm.onTitleRegexInputChange("""Flight\s+([A-Z0-9]+)""")
        vm.onDescriptionRegexInputChange("""Gate\s+([A-Z0-9]+)""")
        vm.onTimeRegexInputChange("""at\s+(\d{1,2}:\d{2})""")
        vm.onSaveParsingRules()
        vm.onDefaultIconSelected(PinIcon.SCHEDULED_FLIGHT)
        vm.onDefaultBackgroundColorSelected(PinAccentColor.SKY)
        advanceUntilIdle()

        val saved = settingsRepo.getSettings()
        assertEquals("flight, gate", saved.keywordFilter)
        assertEquals("""Flight\s+([A-Z0-9]+)""", saved.titleRegexPattern)
        assertEquals("""Gate\s+([A-Z0-9]+)""", saved.descriptionRegexPattern)
        assertEquals("""at\s+(\d{1,2}:\d{2})""", saved.timeRegexPattern)
        assertEquals(PinIcon.SCHEDULED_FLIGHT.uri, saved.defaultIconUri)
        assertEquals(PinAccentColor.SKY.hexCode, saved.defaultBackgroundColor)
        assertNotNull(vm.uiState.value.rulesSavedMessage)
        assertFalse(vm.uiState.value.hasUnsavedRuleEdits)
    }

    @Test
    fun `exposes only notification auto-pins and formats source metadata with resolved app names`() = runTest {
        val manualPin = SentPinRecord(
            id = "ppm-manual-1",
            title = "Dentist",
            startTimeMillis = 1_700_000_000_000L,
            source = EventSource.MANUAL.name,
            sentAtMillis = 1_700_000_003_000L,
        )
        val sbnKeyAutoPin = SentPinRecord(
            id = "ppm-notif-1",
            title = "Flight LH123",
            description = "Gate B12",
            startTimeMillis = 1_700_000_000_000L,
            iconUri = PinIcon.SCHEDULED_FLIGHT.uri,
            backgroundColor = PinAccentColor.SKY.hexCode,
            source = EventSource.NOTIFICATION.name,
            sourceKey = "0|com.example.flights|42|null|1000",
            sentAtMillis = 1_700_000_002_000L,
        )
        val fallbackKeyAutoPin = SentPinRecord(
            id = "ppm-notif-2",
            title = "Package Delivered",
            startTimeMillis = 1_700_000_000_000L,
            source = EventSource.NOTIFICATION.name,
            sourceKey = "com.example.delivery|parcel|9",
            sentAtMillis = 1_700_000_001_000L,
        )
        val customKeyAutoPin = SentPinRecord(
            id = "ppm-notif-3",
            title = "Custom Event",
            startTimeMillis = 1_700_000_000_000L,
            source = EventSource.NOTIFICATION.name,
            sourceKey = "custom-key-7",
            sentAtMillis = 1_700_000_000_500L,
        )
        val noKeyAutoPin = SentPinRecord(
            id = "ppm-notif-4",
            title = "No Key Event",
            startTimeMillis = 1_700_000_000_000L,
            source = EventSource.NOTIFICATION.name,
            sourceKey = null,
            sentAtMillis = 1_700_000_000_100L,
        )

        val vm = NotificationSettingsViewModel(
            settingsRepository = FakeNotificationSettingsRepository(),
            accessStatusChecker = object : NotificationAccessStatusChecker {
                override fun isNotificationAccessEnabled(): Boolean = true
            },
            installedAppsProvider = FakeInstalledAppsProvider(
                listOf(
                    InstalledAppInfo("com.example.flights", "Skyline Flights"),
                    InstalledAppInfo("com.example.delivery", "Parcel Tracker"),
                ),
            ),
            sentPinRepository = FakeNotifSentPinRepository(
                listOf(manualPin, sbnKeyAutoPin, fallbackKeyAutoPin, customKeyAutoPin, noKeyAutoPin),
            ),
            pinSender = FakeNotifTimelinePinSender(),
            clock = fixedClock,
        )
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(4, state.autoPinCount)
        assertEquals(
            listOf("ppm-notif-1", "ppm-notif-2", "ppm-notif-3", "ppm-notif-4"),
            state.autoPins.map { it.id },
        )
        assertEquals(
            "Source: Skyline Flights • 0|com.example.flights|42|null|1000",
            state.formatAutoPinSource(sbnKeyAutoPin),
        )
        assertEquals(
            "Source: Parcel Tracker • com.example.delivery|parcel|9",
            state.formatAutoPinSource(fallbackKeyAutoPin),
        )
        assertEquals(
            "Source: custom-key-7",
            state.formatAutoPinSource(customKeyAutoPin),
        )
        assertEquals(
            "Source: Notification",
            state.formatAutoPinSource(noKeyAutoPin),
        )
    }

    @Test
    fun `deleteAutoPin removes pin via Pebble sender, deletes from repository, and triggers watch sync`() = runTest {
        val autoPin = SentPinRecord(
            id = "ppm-notif-del-1",
            title = "Gate Change B14",
            startTimeMillis = 1_700_000_000_000L,
            source = EventSource.NOTIFICATION.name,
            sourceKey = "0|com.example.flights|42|null|1000",
            sentAtMillis = 1_700_000_000_000L,
        )
        val manualPin = SentPinRecord(
            id = "ppm-manual-keep",
            title = "Dentist",
            startTimeMillis = 1_700_000_000_000L,
            source = EventSource.MANUAL.name,
            sentAtMillis = 1_700_000_000_000L,
        )
        var syncTriggered = 0
        val pinSender = FakeNotifTimelinePinSender()
        val sentPinRepo = FakeNotifSentPinRepository(listOf(autoPin, manualPin))
        val vm = NotificationSettingsViewModel(
            settingsRepository = FakeNotificationSettingsRepository(),
            accessStatusChecker = object : NotificationAccessStatusChecker {
                override fun isNotificationAccessEnabled(): Boolean = true
            },
            installedAppsProvider = FakeInstalledAppsProvider(emptyList()),
            sentPinRepository = sentPinRepo,
            pinSender = pinSender,
            clock = fixedClock,
            onPinsChanged = { syncTriggered++ },
        )
        advanceUntilIdle()

        vm.deleteAutoPin("ppm-notif-del-1")
        advanceUntilIdle()

        assertEquals(listOf("ppm-notif-del-1"), pinSender.deletedPinIds)
        assertEquals(1, syncTriggered)
        assertNull(sentPinRepo.getPinById("ppm-notif-del-1"))
        assertNotNull(sentPinRepo.getPinById("ppm-manual-keep"))

        val state = vm.uiState.value
        assertTrue(state.autoPins.isEmpty())
        assertEquals(0, state.autoPinCount)
        assertNull(state.deletingPinId)
        assertTrue(state.pinActionFeedback is SendFeedback.Success)
        assertEquals(
            "Auto-pin deleted from Pebble Timeline",
            (state.pinActionFeedback as SendFeedback.Success).headline,
        )
        assertTrue((state.pinActionFeedback as SendFeedback.Success).details.contains("Gate Change B14"))

        vm.dismissPinActionFeedback()
        assertNull(vm.uiState.value.pinActionFeedback)
    }

    @Test
    fun `deleteAutoPin removes stale local pin when Pebble reports UnknownPin`() = runTest {
        val autoPin = SentPinRecord(
            id = "ppm-notif-stale",
            title = "Old Flight",
            startTimeMillis = 1_700_000_000_000L,
            source = EventSource.NOTIFICATION.name,
            sentAtMillis = 1_700_000_000_000L,
        )
        var syncTriggered = 0
        val pinSender = FakeNotifTimelinePinSender(
            deleteResult = PinDeleteResult.UnknownPin("ppm-notif-stale"),
        )
        val sentPinRepo = FakeNotifSentPinRepository(listOf(autoPin))
        val vm = NotificationSettingsViewModel(
            settingsRepository = FakeNotificationSettingsRepository(),
            accessStatusChecker = object : NotificationAccessStatusChecker {
                override fun isNotificationAccessEnabled(): Boolean = true
            },
            installedAppsProvider = FakeInstalledAppsProvider(emptyList()),
            sentPinRepository = sentPinRepo,
            pinSender = pinSender,
            clock = fixedClock,
            onPinsChanged = { syncTriggered++ },
        )
        advanceUntilIdle()

        vm.deleteAutoPin("ppm-notif-stale")
        advanceUntilIdle()

        assertEquals(listOf("ppm-notif-stale"), pinSender.deletedPinIds)
        assertEquals(1, syncTriggered)
        assertTrue(vm.uiState.value.autoPins.isEmpty())
        assertTrue(vm.uiState.value.pinActionFeedback is SendFeedback.Success)
        assertEquals(
            "Auto-pin removed from history",
            (vm.uiState.value.pinActionFeedback as SendFeedback.Success).headline,
        )
    }

    @Test
    fun `deleteAutoPin keeps local record intact and shows error when companion app fails`() = runTest {
        val autoPin = SentPinRecord(
            id = "ppm-notif-keep",
            title = "Parcel Out for Delivery",
            startTimeMillis = 1_700_000_000_000L,
            source = EventSource.NOTIFICATION.name,
            sentAtMillis = 1_700_000_000_000L,
        )
        val failureCases = listOf(
            PinDeleteResult.NoPebbleApp to "not available",
            PinDeleteResult.NoPermissions("e4b7c3a1-8f2d-4c6e-9a51-7b3d2e8f1042") to "permission denied",
            PinDeleteResult.UnsupportedAction to "unsupported",
            PinDeleteResult.UnexpectedError("Binder died") to "Failed to delete",
        )

        failureCases.forEach { (deleteResult, expectedHeadlineFragment) ->
            val pinSender = FakeNotifTimelinePinSender(deleteResult = deleteResult)
            val sentPinRepo = FakeNotifSentPinRepository(listOf(autoPin))
            val vm = NotificationSettingsViewModel(
                settingsRepository = FakeNotificationSettingsRepository(),
                accessStatusChecker = object : NotificationAccessStatusChecker {
                    override fun isNotificationAccessEnabled(): Boolean = true
                },
                installedAppsProvider = FakeInstalledAppsProvider(emptyList()),
                sentPinRepository = sentPinRepo,
                pinSender = pinSender,
                clock = fixedClock,
            )
            advanceUntilIdle()

            vm.deleteAutoPin("ppm-notif-keep")
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(1, state.autoPins.size)
            assertNull(state.deletingPinId)
            assertTrue(state.pinActionFeedback is SendFeedback.Error)
            assertTrue(
                "Expected '${(state.pinActionFeedback as SendFeedback.Error).headline}' to contain '$expectedHeadlineFragment'",
                (state.pinActionFeedback as SendFeedback.Error).headline.contains(expectedHeadlineFragment, ignoreCase = true),
            )
        }
    }

    @Test
    fun `manages per-app N regex rules, global N regex rules, custom-rules filter, and live rule tester`() = runTest {
        val gmailPkg = "com.google.android.gm"
        val calendarPkg = "com.google.android.calendar"
        val settingsRepo = FakeNotificationSettingsRepository()
        val vm = NotificationSettingsViewModel(
            settingsRepository = settingsRepo,
            accessStatusChecker = object : NotificationAccessStatusChecker {
                override fun isNotificationAccessEnabled(): Boolean = true
            },
            installedAppsProvider = FakeInstalledAppsProvider(
                listOf(
                    InstalledAppInfo(gmailPkg, "Gmail"),
                    InstalledAppInfo(calendarPkg, "Calendar"),
                ),
            ),
            sentPinRepository = FakeNotifSentPinRepository(),
            pinSender = FakeNotifTimelinePinSender(),
            clock = fixedClock,
        )
        advanceUntilIdle()

        // Add two global regex rules and reorder them
        vm.onUpsertGlobalRegexRule(
            NotificationRegexRule(
                id = "g1",
                name = "Global First",
                matchPattern = "first",
            ),
        )
        vm.onUpsertGlobalRegexRule(
            NotificationRegexRule(
                id = "g2",
                name = "Global Second",
                matchPattern = "second",
            ),
        )
        // Invalid regex rule is rejected cleanly
        vm.onUpsertGlobalRegexRule(
            NotificationRegexRule(
                id = "g-bad",
                name = "Broken",
                matchPattern = "[unclosed",
            ),
        )
        vm.onMoveGlobalRegexRule("g2", -1)
        vm.onToggleGlobalRegexRuleEnabled("g1", false)
        advanceUntilIdle()

        val afterGlobal = settingsRepo.getSettings()
        assertEquals(listOf("g2", "g1"), afterGlobal.globalRegexRules.map { it.id })
        assertFalse(afterGlobal.globalRegexRules.first { it.id == "g1" }.enabled)

        // Configure per-app Gmail rules (with Subject-as-Title preset + Flight rule)
        vm.onSaveAppRuleConfig(
            AppNotificationRuleConfig(
                packageName = gmailPkg,
                requireRegexRuleMatch = true,
                regexRules = listOf(
                    NotificationRegexRule(
                        id = "gm-flight",
                        name = "Flights",
                        matchPattern = "Delta",
                        matchTarget = NotificationTextField.TITLE,
                        titleRegexPattern = """(Flight\s+[A-Z0-9]+)""",
                        titleSource = NotificationTextField.BODY,
                    ),
                ),
            ),
        )
        vm.onAddSubjectAsTitlePreset(gmailPkg)
        advanceUntilIdle()

        val stateWithAppRules = vm.uiState.value
        assertTrue(gmailPkg in stateWithAppRules.settings.allowedPackages)
        assertEquals(1, stateWithAppRules.configuredAppRulesCount)
        assertEquals("2 regex rules", stateWithAppRules.appRuleBadgeLabel(gmailPkg))

        // Filter apps by Custom Rules only
        vm.onShowCustomRulesOnlyChange(true)
        assertEquals(listOf(gmailPkg), vm.uiState.value.filteredInstalledApps.map { it.packageName })

        // Test live rule preview against Gmail
        vm.onRuleTesterPackageChange(gmailPkg)
        vm.onRuleTesterTitleChange("Delta Air Lines")
        vm.onRuleTesterBodyChange("Flight DL789 departs 15:00\nGate A4")
        advanceUntilIdle()

        val eval = vm.uiState.value.ruleTesterEvaluation
        assertNotNull(eval)
        assertNotNull(eval!!.event)
        assertEquals(RuleMatchScope.APP_RULE, eval.matchedScope)
        assertEquals("Flights", eval.matchedRuleName)
        assertEquals("Flight DL789", eval.event!!.title)

        // Reset app rules back to global defaults
        vm.onResetAppRuleConfig(gmailPkg)
        advanceUntilIdle()
        assertEquals(0, vm.uiState.value.configuredAppRulesCount)
        assertNull(vm.uiState.value.appRuleBadgeLabel(gmailPkg))

        // Toggle Developer Mode on and off
        assertFalse(vm.uiState.value.settings.isDevModeEnabled)
        vm.onDevModeEnabledChange(true)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.settings.isDevModeEnabled)
        vm.onDevModeEnabledChange(false)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.settings.isDevModeEnabled)
    }
}

private class FakeNotifTimelinePinSender(
    private val deleteResult: PinDeleteResult? = null,
) : TimelinePinSender {
    val deletedPinIds = mutableListOf<String>()

    override suspend fun sendPin(event: TimelineEvent): PinSendResult = PinSendResult.Success(event.id)

    override suspend fun deletePin(pinId: String): PinDeleteResult {
        deletedPinIds += pinId
        return deleteResult ?: PinDeleteResult.Success(pinId)
    }

    override suspend fun getCompanionStatus(): PebbleCompanionStatus =
        PebbleCompanionStatus.Available("coredevices.coreapp", listOf("coredevices.coreapp"))

    override fun observeConnectedWatches(): Flow<List<ConnectedPebbleWatch>> = flowOf(emptyList())
}

private class FakeNotificationSettingsRepository(
    initial: NotificationRuleSettings = NotificationRuleSettings(),
) : NotificationSettingsRepository {
    private val flow = MutableStateFlow(initial)
    override fun observeSettings(): Flow<NotificationRuleSettings> = flow
    override suspend fun getSettings(): NotificationRuleSettings = flow.value
    override suspend fun updateSettings(transform: (NotificationRuleSettings) -> NotificationRuleSettings) {
        flow.value = transform(flow.value)
    }
}

private class FakeInstalledAppsProvider(
    private val apps: List<InstalledAppInfo>,
) : InstalledAppsProvider {
    override suspend fun loadInstalledApps(): List<InstalledAppInfo> = apps
}

private class FakeNotifSentPinRepository(
    initial: List<SentPinRecord> = emptyList(),
) : SentPinRepository {
    private val flow = MutableStateFlow(initial)
    override fun observeSentPins(): Flow<List<SentPinRecord>> = flow
    override suspend fun saveSentPin(record: SentPinRecord) {
        flow.value = listOf(record) + flow.value.filterNot { it.id == record.id }
    }
    override suspend fun getPinById(id: String): SentPinRecord? = flow.value.firstOrNull { it.id == id }
    override suspend fun getPinBySourceKey(sourceKey: String): SentPinRecord? =
        flow.value.firstOrNull { it.sourceKey == sourceKey }
    override suspend fun deletePinRecord(id: String) {
        flow.value = flow.value.filterNot { it.id == id }
    }
    override suspend fun clearAll() {
        flow.value = emptyList()
    }
}
