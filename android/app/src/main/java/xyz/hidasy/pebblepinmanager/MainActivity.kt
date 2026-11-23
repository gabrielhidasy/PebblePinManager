package xyz.hidasy.pebblepinmanager

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.ViewTimeline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import xyz.hidasy.pebblepinmanager.data.AppSettings
import xyz.hidasy.pebblepinmanager.notifications.PebbleNotificationListenerService
import xyz.hidasy.pebblepinmanager.pebble.AndroidPhoneLaunchEnvironment
import xyz.hidasy.pebblepinmanager.pebble.PhoneHandoffCapabilities
import xyz.hidasy.pebblepinmanager.ui.DevModeRoute
import xyz.hidasy.pebblepinmanager.ui.DevModeViewModel
import xyz.hidasy.pebblepinmanager.ui.DevModeViewModelFactory
import xyz.hidasy.pebblepinmanager.ui.NotificationSettingsRoute
import xyz.hidasy.pebblepinmanager.ui.NotificationSettingsViewModel
import xyz.hidasy.pebblepinmanager.ui.NotificationSettingsViewModelFactory
import xyz.hidasy.pebblepinmanager.ui.PinComposerRoute
import xyz.hidasy.pebblepinmanager.ui.PinComposerViewModel
import xyz.hidasy.pebblepinmanager.ui.PinComposerViewModelFactory
import xyz.hidasy.pebblepinmanager.ui.SimulatedTimelineRoute
import xyz.hidasy.pebblepinmanager.ui.SimulatedTimelineViewModel
import xyz.hidasy.pebblepinmanager.ui.SimulatedTimelineViewModelFactory
import xyz.hidasy.pebblepinmanager.ui.theme.PebblePinManagerTheme

private enum class AppTab {
    COMPOSE,
    NOTIFICATIONS,
    TIMELINE,
    DEV,
}

class MainActivity : ComponentActivity() {
    private val appContainer: AppContainer
        get() = (application as PebblePinManagerApp).container

    private val pinComposerViewModel: PinComposerViewModel by viewModels {
        PinComposerViewModelFactory(
            pinSender = appContainer.pinSender,
            repository = appContainer.sentPinRepository,
            onPinsChanged = { appContainer.watchPinSyncManager.syncRecentPins() },
            runWithoutWatchFlow = appContainer.appSettingsRepository.observeSettings().map { it.runWithoutWatch },
        )
    }

    private val notificationSettingsViewModel: NotificationSettingsViewModel by viewModels {
        NotificationSettingsViewModelFactory(
            settingsRepository = appContainer.notificationSettingsRepository,
            accessStatusChecker = appContainer.notificationAccessStatusChecker,
            installedAppsProvider = appContainer.installedAppsProvider,
            sentPinRepository = appContainer.sentPinRepository,
            pinSender = appContainer.pinSender,
            listenerConnectedFlow = PebbleNotificationListenerService.isListenerConnected,
            onPinsChanged = { appContainer.watchPinSyncManager.syncRecentPins() },
        )
    }

    private val simulatedTimelineViewModel: SimulatedTimelineViewModel by viewModels {
        SimulatedTimelineViewModelFactory(
            simulatedTimelineRepository = appContainer.simulatedTimelineRepository,
            sentPinRepository = appContainer.sentPinRepository,
            pinSender = appContainer.pinSender,
        )
    }

    private val devModeViewModel: DevModeViewModel by viewModels {
        DevModeViewModelFactory(
            rawNotificationRepository = appContainer.rawNotificationRepository,
            settingsRepository = appContainer.notificationSettingsRepository,
            accessStatusChecker = appContainer.notificationAccessStatusChecker,
            installedAppsProvider = appContainer.installedAppsProvider,
            listenerConnectedFlow = PebbleNotificationListenerService.isListenerConnected,
        )
    }

    private var handoffCapabilities: PhoneHandoffCapabilities? by mutableStateOf(null)
    private var isHandoffBannerDismissed: Boolean by mutableStateOf(false)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        refreshHandoffCapabilities()
        if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val canAskAgain = shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
            if (!canAskAgain) {
                openNotificationSettings()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        appContainer.phoneAppLauncher.cancelHandoffNotification()
        isHandoffBannerDismissed = appContainer.watchHandoffPromptPreferences.isPermissionBannerDismissed()
        refreshHandoffCapabilities()
        setContent {
            val appSettings by appContainer.appSettingsRepository
                .observeSettings()
                .collectAsStateWithLifecycle(initialValue = AppSettings())

            PebblePinManagerTheme {
                PebblePinManagerRoot(
                    appSettings = appSettings,
                    onSetRunWithoutWatch = ::setRunWithoutWatch,
                    pinComposerViewModel = pinComposerViewModel,
                    notificationSettingsViewModel = notificationSettingsViewModel,
                    simulatedTimelineViewModel = simulatedTimelineViewModel,
                    devModeViewModel = devModeViewModel,
                    handoffCapabilities = handoffCapabilities,
                    isHandoffBannerDismissed = isHandoffBannerDismissed,
                    onDismissHandoffBanner = ::dismissHandoffBanner,
                    onRequestNotificationPermission = ::requestNotificationPermissionOrSettings,
                    onOpenNotificationSettings = ::openNotificationSettings,
                    onOpenOverlaySettings = ::openOverlaySettings,
                    onOpenNotificationAccessSettings = ::openNotificationAccessSettings,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        appContainer.phoneAppLauncher.cancelHandoffNotification()
        refreshHandoffCapabilities()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        appContainer.phoneAppLauncher.cancelHandoffNotification()
        refreshHandoffCapabilities()
    }

    private fun setRunWithoutWatch(enabled: Boolean) {
        lifecycleScope.launch {
            appContainer.appSettingsRepository.setRunWithoutWatch(enabled)
        }
    }

    private fun refreshHandoffCapabilities() {
        handoffCapabilities = appContainer.phoneAppLauncher.inspectCapabilities()
    }

    private fun dismissHandoffBanner() {
        isHandoffBannerDismissed = true
        appContainer.watchHandoffPromptPreferences.setPermissionBannerDismissed(true)
    }

    private fun requestNotificationPermissionOrSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        openNotificationSettings()
    }

    private fun openNotificationSettings() {
        runCatching {
            startActivity(AndroidPhoneLaunchEnvironment.createNotificationSettingsIntent(this))
        }
    }

    private fun openOverlaySettings() {
        runCatching {
            startActivity(AndroidPhoneLaunchEnvironment.createOverlaySettingsIntent(this))
        }
    }

    private fun openNotificationAccessSettings() {
        val checker = appContainer.notificationAccessStatusChecker
        try {
            startActivity(checker.createSettingsIntent())
        } catch (_: ActivityNotFoundException) {
            runCatching { startActivity(checker.createFallbackSettingsIntent()) }
        }
    }
}

@Composable
private fun PebblePinManagerRoot(
    appSettings: AppSettings,
    onSetRunWithoutWatch: (Boolean) -> Unit,
    pinComposerViewModel: PinComposerViewModel,
    notificationSettingsViewModel: NotificationSettingsViewModel,
    simulatedTimelineViewModel: SimulatedTimelineViewModel,
    devModeViewModel: DevModeViewModel,
    handoffCapabilities: PhoneHandoffCapabilities?,
    isHandoffBannerDismissed: Boolean,
    onDismissHandoffBanner: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenNotificationAccessSettings: () -> Unit,
) {
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.COMPOSE) }
    var showAppSettingsDialog by rememberSaveable { mutableStateOf(false) }
    val notificationState by notificationSettingsViewModel.uiState.collectAsStateWithLifecycle()
    val simulatedTimelineState by simulatedTimelineViewModel.uiState.collectAsStateWithLifecycle()
    val devModeState by devModeViewModel.uiState.collectAsStateWithLifecycle()
    val isDevModeEnabled = notificationState.settings.isDevModeEnabled

    LaunchedEffect(appSettings.runWithoutWatch) {
        if (!appSettings.runWithoutWatch && selectedTab == AppTab.TIMELINE) {
            selectedTab = AppTab.COMPOSE
        }
    }

    LaunchedEffect(isDevModeEnabled) {
        if (!isDevModeEnabled && selectedTab == AppTab.DEV) {
            selectedTab = AppTab.NOTIFICATIONS
        }
    }

    val bottomNavigation: @Composable () -> Unit = {
        NavigationBar {
            NavigationBarItem(
                selected = selectedTab == AppTab.COMPOSE,
                onClick = { selectedTab = AppTab.COMPOSE },
                icon = {
                    Icon(
                        imageVector = Icons.Default.CalendarMonth,
                        contentDescription = "Timeline Pins",
                    )
                },
                label = { Text("Pins") },
            )
            NavigationBarItem(
                selected = selectedTab == AppTab.NOTIFICATIONS,
                onClick = { selectedTab = AppTab.NOTIFICATIONS },
                icon = {
                    val active = notificationState.isNotificationAccessEnabled &&
                        notificationState.settings.isAutoPinEnabled &&
                        notificationState.allowedCount > 0
                    val notifIcon = if (active) {
                        Icons.Default.NotificationsActive
                    } else {
                        Icons.Default.Notifications
                    }
                    BadgedBox(
                        badge = {
                            if (notificationState.allowedCount > 0) {
                                Badge { Text(notificationState.allowedCount.toString()) }
                            }
                        },
                    ) {
                        Icon(
                            imageVector = notifIcon,
                            contentDescription = "Auto-Pins",
                        )
                    }
                },
                label = { Text("Auto-Pins") },
            )
            if (appSettings.runWithoutWatch) {
                NavigationBarItem(
                    selected = selectedTab == AppTab.TIMELINE,
                    onClick = { selectedTab = AppTab.TIMELINE },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (simulatedTimelineState.activePinCount > 0) {
                                    Badge { Text(simulatedTimelineState.activePinCount.toString()) }
                                }
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Default.ViewTimeline,
                                contentDescription = "Simulated Timeline",
                            )
                        }
                    },
                    label = { Text("Timeline") },
                )
            }
            if (isDevModeEnabled) {
                NavigationBarItem(
                    selected = selectedTab == AppTab.DEV,
                    onClick = { selectedTab = AppTab.DEV },
                    icon = {
                        BadgedBox(
                            badge = {
                                val count = devModeState.rawNotifications.size
                                if (count > 0) {
                                    Badge { Text(count.toString()) }
                                }
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Default.Code,
                                contentDescription = "Dev Inspector",
                            )
                        }
                    },
                    label = { Text("Dev") },
                )
            }
        }
    }

    when (selectedTab) {
        AppTab.COMPOSE -> PinComposerRoute(
            viewModel = pinComposerViewModel,
            handoffCapabilities = handoffCapabilities,
            isHandoffBannerDismissed = isHandoffBannerDismissed,
            onDismissHandoffBanner = onDismissHandoffBanner,
            onRequestNotificationPermission = onRequestNotificationPermission,
            onOpenNotificationSettings = onOpenNotificationSettings,
            onOpenOverlaySettings = onOpenOverlaySettings,
            onOpenSettings = { showAppSettingsDialog = true },
            onSetRunWithoutWatch = onSetRunWithoutWatch,
            onOpenSimulatedTimeline = if (appSettings.runWithoutWatch) {
                { selectedTab = AppTab.TIMELINE }
            } else {
                null
            },
            bottomBar = bottomNavigation,
        )
        AppTab.NOTIFICATIONS -> NotificationSettingsRoute(
            viewModel = notificationSettingsViewModel,
            onOpenNotificationAccessSettings = onOpenNotificationAccessSettings,
            onEditAutoPin = { record ->
                pinComposerViewModel.startEditingPin(record)
                selectedTab = AppTab.COMPOSE
            },
            onOpenSettings = { showAppSettingsDialog = true },
            bottomBar = bottomNavigation,
        )
        AppTab.TIMELINE -> SimulatedTimelineRoute(
            viewModel = simulatedTimelineViewModel,
            onEditPin = { record ->
                pinComposerViewModel.startEditingPin(record)
                selectedTab = AppTab.COMPOSE
            },
            onCreatePin = { selectedTab = AppTab.COMPOSE },
            onOpenSettings = { showAppSettingsDialog = true },
            bottomBar = bottomNavigation,
        )
        AppTab.DEV -> DevModeRoute(
            viewModel = devModeViewModel,
            onOpenNotificationAccessSettings = onOpenNotificationAccessSettings,
            bottomBar = bottomNavigation,
        )
    }

    if (showAppSettingsDialog) {
        AppSettingsDialog(
            runWithoutWatch = appSettings.runWithoutWatch,
            onRunWithoutWatchChange = onSetRunWithoutWatch,
            onDismiss = { showAppSettingsDialog = false },
        )
    }
}

@Composable
private fun AppSettingsDialog(
    runWithoutWatch: Boolean,
    onRunWithoutWatchChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("App Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = "Run without a watch",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Simulate Pebble timeline delivery locally without a Pebble watch or companion app, and add a Timeline tab showing what would have been sent.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = runWithoutWatch,
                        onCheckedChange = onRunWithoutWatchChange,
                    )
                }
                if (runWithoutWatch) {
                    Text(
                        text = "Watchless mode is active. Use the Timeline tab in the bottom bar to inspect captured pins and PebbleKit 2 payloads.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        },
    )
}
