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
import xyz.hidasy.pebblepinmanager.notifications.InstalledAppInfo
import xyz.hidasy.pebblepinmanager.notifications.InstalledAppsProvider
import xyz.hidasy.pebblepinmanager.notifications.NotificationAccessStatusChecker
import xyz.hidasy.pebblepinmanager.notifications.NotificationRuleSettings
import xyz.hidasy.pebblepinmanager.notifications.NotificationSettingsRepository
import xyz.hidasy.pebblepinmanager.notifications.NotificationTextField
import xyz.hidasy.pebblepinmanager.notifications.RawNotificationRecord
import xyz.hidasy.pebblepinmanager.notifications.RawNotificationRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class DevModeViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val fixedInstant = Instant.parse("2026-11-23T10:00:00Z")
    private val fixedClock = Clock.fixed(fixedInstant, ZoneId.of("UTC"))

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `snapshots active notifications, filters raw list, explores regexes, and saves app and global rules`() = runTest {
        val rawRepo = FakeRawNotificationRepository()
        val settingsRepo = FakeDevSettingsRepository(
            NotificationRuleSettings(isDevModeEnabled = true),
        )
        val gmailRecord = RawNotificationRecord(
            id = "raw-gm",
            capturedAtMillis = fixedInstant.toEpochMilli(),
            sourcePackage = "com.google.android.gm",
            appLabel = "Gmail",
            notificationKey = "0|com.google.android.gm|1|null|1000",
            title = "Delta Air Lines",
            text = "Flight DL900 departs 18:15",
            bigText = "Flight DL900 departs 18:15\nGate C7",
            eventTimeMillis = fixedInstant.toEpochMilli(),
        )
        val ongoingRecord = RawNotificationRecord(
            id = "raw-vpn",
            capturedAtMillis = fixedInstant.toEpochMilli() - 10_000L,
            sourcePackage = "com.wireguard.android",
            appLabel = "WireGuard",
            notificationKey = "0|com.wireguard.android|2|null|1000",
            title = "VPN Connected",
            text = "Home tunnel active",
            eventTimeMillis = fixedInstant.toEpochMilli() - 10_000L,
            isOngoing = true,
        )

        val vm = DevModeViewModel(
            rawNotificationRepository = rawRepo,
            settingsRepository = settingsRepo,
            accessStatusChecker = object : NotificationAccessStatusChecker {
                override fun isNotificationAccessEnabled(): Boolean = true
            },
            installedAppsProvider = object : InstalledAppsProvider {
                override suspend fun loadInstalledApps(): List<InstalledAppInfo> = listOf(
                    InstalledAppInfo("com.google.android.gm", "Gmail"),
                    InstalledAppInfo("com.wireguard.android", "WireGuard"),
                )
            },
            listenerConnectedFlow = MutableStateFlow(true),
            activeNotificationsProvider = { listOf(gmailRecord, ongoingRecord) },
            clock = fixedClock,
        )
        advanceUntilIdle()

        // Snapshot active notifications
        vm.snapshotActiveNotifications()
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.rawNotifications.size)
        assertTrue(vm.uiState.value.statusMessage?.contains("2 active") == true)

        // Filter out ongoing and search
        vm.onHideOngoingChange(true)
        assertEquals(listOf("raw-gm"), vm.uiState.value.filteredNotifications.map { it.id })

        vm.onHideOngoingChange(false)
        vm.onSearchQueryChange("wireguard")
        assertEquals(listOf("raw-vpn"), vm.uiState.value.filteredNotifications.map { it.id })
        vm.onSearchQueryChange("")

        // Select Gmail notification & test regex scratchpad
        vm.onSelectNotification("raw-gm")
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.selectedNotification)

        vm.onScratchpadFieldChange(NotificationTextField.BODY)
        vm.onScratchpadPatternChange("""(Flight\s+[A-Z0-9]+)""")
        assertTrue(vm.uiState.value.scratchpadResult?.isMatched == true)
        assertEquals("Flight DL900", vm.uiState.value.scratchpadResult?.extractedValue)

        // Copy scratchpad regex into Draft Rule title & match
        vm.onApplyScratchpadToTitle()
        vm.onScratchpadFieldChange(NotificationTextField.TITLE)
        vm.onScratchpadPatternChange("Delta")
        vm.onApplyScratchpadToMatch()
        vm.onDraftRuleChange { it.copy(name = "Delta Flights", timeRegexPattern = """departs\s+(\d{1,2}:\d{2})""") }
        advanceUntilIdle()

        val exploration = vm.uiState.value.draftExploration
        assertNotNull(exploration)
        assertEquals("Flight DL900", exploration!!.draftEvaluation.event?.title)
        assertEquals(
            Instant.parse("2026-11-23T18:15:00Z").toEpochMilli(),
            exploration.draftEvaluation.event?.startTimeMillis,
        )

        // Save directly as App Rule -> persists rule and allowlists Gmail
        vm.onSaveDraftAsAppRule()
        advanceUntilIdle()

        val updatedSettings = settingsRepo.getSettings()
        assertTrue("com.google.android.gm" in updatedSettings.allowedPackages)
        val savedAppRule = updatedSettings.appRuleFor("com.google.android.gm")
        assertNotNull(savedAppRule)
        assertEquals(1, savedAppRule!!.regexRules.size)
        assertEquals("Delta Flights", savedAppRule.regexRules.first().name)
        assertEquals("Delta", savedAppRule.regexRules.first().matchPattern)
        assertEquals("""(Flight\s+[A-Z0-9]+)""", savedAppRule.regexRules.first().titleRegexPattern)

        // Current settings evaluation now also creates a pin for this notification!
        assertNotNull(vm.uiState.value.draftExploration?.currentSettingsEvaluation?.event)

        // Save a global rule as well
        vm.onResetDraftRule()
        vm.onDraftRuleChange { it.copy(name = "Global Flight", matchPattern = "Flight") }
        vm.onSaveDraftAsGlobalRule()
        advanceUntilIdle()
        assertEquals(1, settingsRepo.getSettings().globalRegexRules.size)
        assertEquals("Global Flight", settingsRepo.getSettings().globalRegexRules.first().name)

        // Clear notifications resets selection
        vm.onClearAllNotifications()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.rawNotifications.isEmpty())
        assertNull(vm.uiState.value.selectedNotification)
        assertFalse(vm.uiState.value.statusMessage.isNullOrBlank())
    }
}

private class FakeRawNotificationRepository(
    initial: List<RawNotificationRecord> = emptyList(),
) : RawNotificationRepository {
    private val flow = MutableStateFlow(initial)
    override fun observeNotifications(): Flow<List<RawNotificationRecord>> = flow
    override suspend fun getNotifications(): List<RawNotificationRecord> = flow.value
    override suspend fun recordNotification(record: RawNotificationRecord) {
        recordAll(listOf(record))
    }
    override suspend fun recordAll(records: List<RawNotificationRecord>) {
        val byFp = LinkedHashMap<String, RawNotificationRecord>()
        records.sortedByDescending { it.capturedAtMillis }.forEach { byFp.putIfAbsent(it.contentFingerprint(), it) }
        flow.value.forEach { byFp.putIfAbsent(it.contentFingerprint(), it) }
        flow.value = byFp.values.sortedByDescending { it.capturedAtMillis }
    }
    override suspend fun deleteNotification(id: String) {
        flow.value = flow.value.filterNot { it.id == id }
    }
    override suspend fun clearAll() {
        flow.value = emptyList()
    }
}

private class FakeDevSettingsRepository(
    initial: NotificationRuleSettings = NotificationRuleSettings(),
) : NotificationSettingsRepository {
    private val flow = MutableStateFlow(initial)
    override fun observeSettings(): Flow<NotificationRuleSettings> = flow
    override suspend fun getSettings(): NotificationRuleSettings = flow.value
    override suspend fun updateSettings(transform: (NotificationRuleSettings) -> NotificationRuleSettings) {
        flow.value = transform(flow.value)
    }
}
