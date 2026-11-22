package xyz.hidasy.pebblepinmanager.pebble

import java.util.UUID

/**
 * Shared PebbleKit Android 2 configuration constants for PebblePinManager.
 *
 * When paired with the companion watchapp (#1), its `package.json` should declare this UUID and
 * authorize both release (`xyz.hidasy.pebblepinmanager`) and debug
 * (`xyz.hidasy.pebblepinmanager.debug`) Android packages under `pebble.companionApp.android.apps`.
 */
object PebblePinConfig {
    /**
     * Originating Pebble watchapp UUID used when inserting/deleting timeline pins via PebbleKit 2.
     */
    val DEFAULT_WATCHAPP_UUID: UUID = UUID.fromString("e4b7c3a1-8f2d-4c6e-9a51-7b3d2e8f1042")
}
