package xyz.hidasy.pebblepinmanager

import android.app.Application
import xyz.hidasy.pebblepinmanager.data.DataStoreSentPinRepository
import xyz.hidasy.pebblepinmanager.data.SentPinRepository
import xyz.hidasy.pebblepinmanager.pebble.PebbleKit2PinSender
import xyz.hidasy.pebblepinmanager.pebble.TimelinePinSender

/**
 * Lightweight manual dependency container shared across Activities and future background
 * components (such as NotificationListenerService in Issue #7).
 */
class AppContainer(application: Application) {
    val pinSender: TimelinePinSender by lazy {
        PebbleKit2PinSender.create(application)
    }

    val sentPinRepository: SentPinRepository by lazy {
        DataStoreSentPinRepository.create(application)
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
