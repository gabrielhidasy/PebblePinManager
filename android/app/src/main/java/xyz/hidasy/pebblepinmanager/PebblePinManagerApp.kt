package xyz.hidasy.pebblepinmanager

import android.app.Application
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.client.PebbleSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import xyz.hidasy.pebblepinmanager.data.DataStoreSentPinRepository
import xyz.hidasy.pebblepinmanager.data.SentPinRepository
import xyz.hidasy.pebblepinmanager.notifications.AndroidInstalledAppsProvider
import xyz.hidasy.pebblepinmanager.notifications.AndroidNotificationAccessStatusChecker
import xyz.hidasy.pebblepinmanager.notifications.DataStoreNotificationSettingsRepository
import xyz.hidasy.pebblepinmanager.notifications.DataStoreRawNotificationRepository
import xyz.hidasy.pebblepinmanager.notifications.InstalledAppsProvider
import xyz.hidasy.pebblepinmanager.notifications.NotificationPinPipeline
import xyz.hidasy.pebblepinmanager.notifications.NotificationSettingsRepository
import xyz.hidasy.pebblepinmanager.notifications.RawNotificationRepository
import xyz.hidasy.pebblepinmanager.notifications.RuleBasedNotificationPinMapper
import xyz.hidasy.pebblepinmanager.pebble.AndroidPhoneLaunchEnvironment
import xyz.hidasy.pebblepinmanager.pebble.AppForegroundTracker
import xyz.hidasy.pebblepinmanager.pebble.PebbleKit2PinSender
import xyz.hidasy.pebblepinmanager.pebble.PebbleKit2WatchAppMessenger
import xyz.hidasy.pebblepinmanager.pebble.PhoneAppLauncher
import xyz.hidasy.pebblepinmanager.pebble.TimelinePinSender
import xyz.hidasy.pebblepinmanager.pebble.WatchAppMessenger
import xyz.hidasy.pebblepinmanager.pebble.WatchHandoffPromptPreferences
import xyz.hidasy.pebblepinmanager.pebble.WatchPinSyncManager

/**
 * Lightweight manual dependency container shared across Activities, PebbleKit 2 listener services,
 * and background components (such as [xyz.hidasy.pebblepinmanager.notifications.PebbleNotificationListenerService]).
 */
class AppContainer(private val application: Application) {
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val foregroundTracker: AppForegroundTracker = AppForegroundTracker()

    private val sharedPebbleSender: PebbleSender by lazy {
        DefaultPebbleSender(application.applicationContext)
    }

    val pinSender: TimelinePinSender by lazy {
        PebbleKit2PinSender.create(
            context = application,
            pebbleSender = sharedPebbleSender,
        )
    }

    val sentPinRepository: SentPinRepository by lazy {
        DataStoreSentPinRepository.create(application)
    }

    val watchAppMessenger: WatchAppMessenger by lazy {
        PebbleKit2WatchAppMessenger(pebbleSender = sharedPebbleSender)
    }

    val phoneAppLauncher: PhoneAppLauncher by lazy {
        AndroidPhoneLaunchEnvironment.createLauncher(
            context = application,
            foregroundTracker = foregroundTracker,
        )
    }

    val watchHandoffPromptPreferences: WatchHandoffPromptPreferences by lazy {
        WatchHandoffPromptPreferences(application)
    }

    val watchPinSyncManager: WatchPinSyncManager by lazy {
        WatchPinSyncManager(
            pinSender = pinSender,
            repository = sentPinRepository,
            messenger = watchAppMessenger,
            onOpenPhoneUi = { phoneAppLauncher.openPhoneUi() },
        )
    }

    val notificationSettingsRepository: NotificationSettingsRepository by lazy {
        DataStoreNotificationSettingsRepository.create(application)
    }

    val rawNotificationRepository: RawNotificationRepository by lazy {
        DataStoreRawNotificationRepository.create(application)
    }

    val notificationAccessStatusChecker: AndroidNotificationAccessStatusChecker by lazy {
        AndroidNotificationAccessStatusChecker(application)
    }

    val installedAppsProvider: InstalledAppsProvider by lazy {
        AndroidInstalledAppsProvider(application)
    }

    val notificationPinPipeline: NotificationPinPipeline by lazy {
        NotificationPinPipeline(
            mapper = RuleBasedNotificationPinMapper(
                settingsProvider = { notificationSettingsRepository.getSettings() },
            ),
            pinSender = pinSender,
            repository = sentPinRepository,
        )
    }

    fun close() {
        applicationScope.cancel()
        pinSender.close()
    }
}

class PebblePinManagerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        registerActivityLifecycleCallbacks(container.foregroundTracker)
    }

    override fun onTerminate() {
        unregisterActivityLifecycleCallbacks(container.foregroundTracker)
        container.close()
        super.onTerminate()
    }
}
