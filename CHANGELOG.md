# Changelog

All notable user-facing changes to the **PebblePinManager** Android app (`.apk`) and companion Pebble watchapp (`.pbw`) are documented in this file.

Entries are kept concise (1–2 lines per PR relevant to the APK or PBW). CI, documentation-only, and internal tooling changes are omitted.

## [Unreleased]

- **Android (APK):** Add an in-app **Privacy Policy** dialog accessible from the app bar and Settings, summarizing PebblePinManager's 100% offline, zero-collection privacy guarantees.
- **Android (APK):** Remove the stray teal notification dot badge from the launcher icon for a cleaner app icon.
- **Android (APK):** Show a **Prominent Disclosure** modal explaining how notification text is processed locally on-device before opening Android's Notification Access settings.
- **Android (APK):** Exclude the Developer Mode raw notification log from Android Auto Backup and device-to-device transfers.
- **Android (APK):** Split release builds into a standard APK (without Developer Mode) and an optional `-dev` APK that enables the Developer Mode tab for raw notification inspection and regex exploration.

## [v0.0.2] - 2026-11-23

- **Android (APK):** Add **Run without a watch** mode with a **Simulated Timeline** tab so users can compose, preview, and test pins without a paired Pebble watch.
- **Android (APK):** Add a **Developer Mode** tab with a live raw notification inspector and interactive regex rule explorer.
- **Android (APK):** Support multiple per-app and global regex rules for automatic notification-to-pin extraction.
- **Android (APK):** Display auto-created timeline pins on the **Auto-Pins** tab alongside manual pins.
- **Android (APK):** Add opt-in on-device **Notification Access** (`NotificationListenerService`) to automatically convert matching notifications into Pebble timeline pins.
- **Android (APK):** Redesign the launcher icon with a phone and Pebble watch motif.
- **Android (APK):** Handle Android 10+ background activity launch (BAL) restrictions when launching the phone app from the watch.
- **Android (APK):** Show the resolved local payload time box and add a visual icon grid picker in the pin composer.
- **Android (APK):** Update `+15 min` and `+1 hour` quick-offset buttons to shift from the currently selected payload time and add a **Now** reset button.
- **Watchapp (PBW) & Android (APK):** Sync recent sent pins to the Pebble watchapp with an on-watch recent pins menu, **Reschedule**, and **Delete** actions.
- **Android (APK):** Add an adaptive two-pane layout for foldables and landscape displays.
- **Android (APK):** Support editing and deleting previously sent timeline pins with local DataStore persistence.
- **Android (APK):** Allow customizing each pin's icon, duration, and accent color.
- **Watchapp (PBW) & Android (APK):** Initial Android companion app (Kotlin + Jetpack Compose + PebbleKit Android 2) and Pebble watchapp (`basalt` & `emery`) for sending local timeline pins.
