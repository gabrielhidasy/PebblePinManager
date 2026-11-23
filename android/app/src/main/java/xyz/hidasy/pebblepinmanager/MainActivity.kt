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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.hidasy.pebblepinmanager.notifications.PebbleNotificationListenerService
import xyz.hidasy.pebblepinmanager.pebble.AndroidPhoneLaunchEnvironment
import xyz.hidasy.pebblepinmanager.pebble.PhoneHandoffCapabilities
import xyz.hidasy.pebblepinmanager.ui.NotificationSettingsRoute
import xyz.hidasy.pebblepinmanager.ui.NotificationSettingsViewModel
import xyz.hidasy.pebblepinmanager.ui.NotificationSettingsViewModelFactory
import xyz.hidasy.pebblepinmanager.ui.PinComposerRoute
import xyz.hidasy.pebblepinmanager.ui.PinComposerViewModel
import xyz.hidasy.pebblepinmanager.ui.PinComposerViewModelFactory
import xyz.hidasy.pebblepinmanager.ui.theme.PebblePinManagerTheme

private enum class AppTab {
    COMPOSE,
    NOTIFICATIONS,
}

class MainActivity : ComponentActivity() {
    private val appContainer: AppContainer
        get() = (application as PebblePinManagerApp).container

    private val pinComposerViewModel: PinComposerViewModel by viewModels {
        PinComposerViewModelFactory(
            pinSender = appContainer.pinSender,
            repository = appContainer.sentPinRepository,
            onPinsChanged = { appContainer.watchPinSyncManager.syncRecentPins() },
        )
    }

    private val notificationSettingsViewModel: NotificationSettingsViewModel by viewModels {
        NotificationSettingsViewModelFactory(
            settingsRepository = appContainer.notificationSettingsRepository,
            accessStatusChecker = appContainer.notificationAccessStatusChecker,
            installedAppsProvider = appContainer.installedAppsProvider,
            sentPinRepository = appContainer.sentPinRepository,
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
            PebblePinManagerTheme {
                PebblePinManagerRoot(
                    pinComposerViewModel = pinComposerViewModel,
                    notificationSettingsViewModel = notificationSettingsViewModel,
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
    pinComposerViewModel: PinComposerViewModel,
    notificationSettingsViewModel: NotificationSettingsViewModel,
    handoffCapabilities: PhoneHandoffCapabilities?,
    isHandoffBannerDismissed: Boolean,
    onDismissHandoffBanner: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenNotificationAccessSettings: () -> Unit,
) {
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.COMPOSE) }
    val notificationState by notificationSettingsViewModel.uiState.collectAsStateWithLifecycle()

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
            bottomBar = bottomNavigation,
        )
        AppTab.NOTIFICATIONS -> NotificationSettingsRoute(
            viewModel = notificationSettingsViewModel,
            onOpenNotificationAccessSettings = onOpenNotificationAccessSettings,
            bottomBar = bottomNavigation,
        )
    }
}
