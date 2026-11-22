package xyz.hidasy.pebblepinmanager.emulatorbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Allows the E2E harness to switch emulated Pebble watch platform (`basalt` / `emery`),
 * connection state, and host bridge URL at runtime via:
 *
 * ```bash
 * adb shell am broadcast \
 *   -a xyz.hidasy.pebblepinmanager.emulatorbridge.CONFIGURE \
 *   -n xyz.hidasy.pebblepinmanager.emulatorbridge/.BridgeConfigReceiver \
 *   --es platform basalt \
 *   --es bridge_url http://127.0.0.1:18442 \
 *   --ez connected true
 * ```
 */
class BridgeConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CONFIGURE) return

        val platform = intent.getStringExtra(EXTRA_PLATFORM)
        val watchName = intent.getStringExtra(EXTRA_WATCH_NAME)
        val bridgeUrl = intent.getStringExtra(EXTRA_BRIDGE_URL)
        val connected = if (intent.hasExtra(EXTRA_CONNECTED)) {
            intent.getBooleanExtra(EXTRA_CONNECTED, true)
        } else {
            null
        }

        val updated = BridgeConfigStore.update(
            context = context,
            platform = platform,
            watchName = watchName,
            bridgeUrl = bridgeUrl,
            connected = connected,
        )
        EmulatorBridgeProvider.initializeIfCreated()
        Log.i(TAG, "Updated emulator bridge config: $updated")
    }

    companion object {
        const val ACTION_CONFIGURE = "xyz.hidasy.pebblepinmanager.emulatorbridge.CONFIGURE"
        const val EXTRA_PLATFORM = "platform"
        const val EXTRA_WATCH_NAME = "watch_name"
        const val EXTRA_BRIDGE_URL = "bridge_url"
        const val EXTRA_CONNECTED = "connected"
        private const val TAG = "BridgeConfigReceiver"
    }
}
