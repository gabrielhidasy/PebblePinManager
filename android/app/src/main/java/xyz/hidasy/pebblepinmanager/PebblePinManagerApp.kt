package xyz.hidasy.pebblepinmanager

import android.app.Application
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.client.PebbleSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.map
import xyz.hidasy.pebblepinmanager.data.AppSettingsRepository
import xyz.hidasy.pebblepinmanager.data.DataStoreAppSettingsRepository
import xyz.hidasy.pebblepinmanager.data.DataStoreSentPinRepository
import xyz.hidasy.pebblepinmanager.data.DataStoreSimulatedTimelineRepository
import xyz.hidasy.pebblepinmanager.data.SentPinRepository
import xyz.hidasy.pebblepinmanager.data.SimulatedTimelineRepository
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
import xyz.hidasy.pebblepinmanager.pebble.SimulatedTimelinePinSender
import xyz.hidasy.pebblepinmanager.pebble.SwitchableTimelinePinSender
import xyz.hidasy.pebblepinmanager.pebble.SwitchableWatchAppMessenger
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

    val appSettingsRepository: AppSettingsRepository by lazy {
        DataStoreAppSettingsRepository.create(application)
    }

    val simulatedTimelineRepository: SimulatedTimelineRepository by lazy {
        DataStoreSimulatedTimelineRepository.create(application)
    }

    private val sharedPebbleSender: PebbleSender by lazy {
        DefaultPebbleSender(application.applicationContext)
    }

    private val realPinSender: TimelinePinSender by lazy {
        PebbleKit2PinSender.create(
            context = application,
            pebbleSender = sharedPebbleSender,
        )
    }

    private val simulatedPinSender: TimelinePinSender by lazy {
        SimulatedTimelinePinSender(
            simulatedRepository = simulatedTimelineRepository,
        )
    }

    val pinSender: TimelinePinSender by lazy {
        SwitchableTimelinePinSender(
            realSender = realPinSender,
            simulatedSender = simulatedPinSender,
            runWithoutWatchFlow = appSettingsRepository.observeSettings().map { it.runWithoutWatch },
            isRunWithoutWatch = { appSettingsRepository.getSettings().runWithoutWatch },
        )
    }

    val sentPinRepository: SentPinRepository by lazy {
        DataStoreSentPinRepository.create(application)
    }

    val watchAppMessenger: WatchAppMessenger by lazy {
        SwitchableWatchAppMessenger(
            realMessenger = PebbleKit2WatchAppMessenger(pebbleSender = sharedPebbleSender),
            isRunWithoutWatch = { appSettingsRepository.getSettings().runWithoutWatch },
        )
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
