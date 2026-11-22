package xyz.hidasy.pebblepinmanager

import android.app.Application
import android.content.Intent
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.client.PebbleSender
import xyz.hidasy.pebblepinmanager.data.DataStoreSentPinRepository
import xyz.hidasy.pebblepinmanager.data.SentPinRepository
import xyz.hidasy.pebblepinmanager.pebble.PebbleKit2PinSender
import xyz.hidasy.pebblepinmanager.pebble.PebbleKit2WatchAppMessenger
import xyz.hidasy.pebblepinmanager.pebble.TimelinePinSender
import xyz.hidasy.pebblepinmanager.pebble.WatchAppMessenger
import xyz.hidasy.pebblepinmanager.pebble.WatchPinSyncManager

/**
 * Lightweight manual dependency container shared across Activities, PebbleKit 2 listener services,
 * and future background components (such as NotificationListenerService in Issue #7).
 */
class AppContainer(private val application: Application) {
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

    val watchPinSyncManager: WatchPinSyncManager by lazy {
        WatchPinSyncManager(
            pinSender = pinSender,
            repository = sentPinRepository,
            messenger = watchAppMessenger,
            onOpenPhoneUi = { openPhoneUi() },
        )
    }

    private fun openPhoneUi(): Boolean {
        return runCatching {
            val intent = Intent(application.applicationContext, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP,
                )
            }
            application.applicationContext.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    fun close() {
        pinSender.close()
    }
}

class PebblePinManagerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun onTerminate() {
        container.close()
        super.onTerminate()
    }
}
