package xyz.hidasy.pebblepinmanager

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import xyz.hidasy.pebblepinmanager.pebble.AndroidPhoneLaunchEnvironment
import xyz.hidasy.pebblepinmanager.pebble.PhoneHandoffCapabilities
import xyz.hidasy.pebblepinmanager.ui.PinComposerRoute
import xyz.hidasy.pebblepinmanager.ui.PinComposerViewModel
import xyz.hidasy.pebblepinmanager.ui.PinComposerViewModelFactory
import xyz.hidasy.pebblepinmanager.ui.theme.PebblePinManagerTheme

class MainActivity : ComponentActivity() {
    private val appContainer: AppContainer
        get() = (application as PebblePinManagerApp).container

    private val viewModel: PinComposerViewModel by viewModels {
        PinComposerViewModelFactory(
            pinSender = appContainer.pinSender,
            repository = appContainer.sentPinRepository,
            onPinsChanged = { appContainer.watchPinSyncManager.syncRecentPins() },
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
                PinComposerRoute(
                    viewModel = viewModel,
                    handoffCapabilities = handoffCapabilities,
                    isHandoffBannerDismissed = isHandoffBannerDismissed,
                    onDismissHandoffBanner = ::dismissHandoffBanner,
                    onRequestNotificationPermission = ::requestNotificationPermissionOrSettings,
                    onOpenNotificationSettings = ::openNotificationSettings,
                    onOpenOverlaySettings = ::openOverlaySettings,
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
}
