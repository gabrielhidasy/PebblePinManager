package xyz.hidasy.pebblepinmanager.emulatorbridge

import android.app.Application
import android.util.Log

class EmulatorBridgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        BridgeConfigStore.init(this)
        EmulatorBridgeProvider.initializeIfCreated()
        Log.i(TAG, "Initialized PebbleKit2 Emulator Bridge (${BridgeConfigStore.configFlow.value})")
    }

    companion object {
        private const val TAG = "EmulatorBridgeApp"
    }
}
