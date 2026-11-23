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
import xyz.hidasy.pebblepinmanager.notifications.InstalledAppInfo
import xyz.hidasy.pebblepinmanager.notifications.InstalledAppsProvider
import xyz.hidasy.pebblepinmanager.notifications.NotificationAccessStatusChecker
import xyz.hidasy.pebblepinmanager.notifications.NotificationRuleSettings
import xyz.hidasy.pebblepinmanager.notifications.NotificationSettingsRepository

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationSettingsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

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
            listenerConnectedFlow = listenerFlow,
        )
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isNotificationAccessEnabled)
        assertEquals(3, vm.uiState.value.installedApps.size)
        assertEquals(1, vm.uiState.value.autoPinCount)

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
