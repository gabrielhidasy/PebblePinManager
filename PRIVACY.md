# Privacy Policy for PebblePinManager

**Effective Date:** November 23, 2026  
**Applies to:** PebblePinManager Android app (`xyz.hidasy.pebblepinmanager`) and the companion Pebble watchapp (`e4b7c3a1-8f2d-4c6e-9a51-7b3d2e8f1042`).

## Summary

PebblePinManager is designed to work **100% locally** on your Android device and your paired Pebble smartwatch:

- **No internet access:** The Android app does not request the `INTERNET` permission and cannot connect to any external server. The Pebble watchapp has no webview or PebbleKit JS component and makes no network requests.
- **No data collection or sharing:** PebblePinManager does not collect, transmit, sell, or share any personal information.
- **No telemetry, analytics, crash reporting, or ads:** The app contains no third-party analytics, tracking, crash-reporting, or advertising SDKs.

## Data Handled Locally on Your Device

### 1. Pin History & App Preferences
When you create or edit Timeline pins in PebblePinManager, the app stores your pin details (pin ID, title, optional description, event date/time, duration, icon, accent color, and local source metadata — up to the 100 most recent pins) strictly on your device using AndroidX Preferences DataStore (`sent_timeline_pins`). Basic UI preferences (such as whether you dismissed the watch-handoff permission banner) are also stored locally on your device.

If you have enabled Android's system-level device backup on your phone, Android Auto Backup (`android:allowBackup="true"`) may include your pin history (`sent_timeline_pins`) and app preferences in your personal device backup according to your Android system settings. Optional Developer Mode raw notification logs (`raw_notifications_dev_log`) are explicitly excluded from Android Auto Backup (cloud backup) and device-to-device transfer via `data_extraction_rules.xml` and `backup_rules.xml`. PebblePinManager itself does not access, transmit, or receive your backup data.

### 2. Local Communication with Your Paired Pebble Watch
PebblePinManager uses **PebbleKit Android 2** (`libpebble3`) local Android IPC to discover an installed Pebble companion app on your phone (such as the Core Devices Pebble app or microPebble), inspect connected watch status, insert/update/delete Timeline pins, and exchange AppMessages with the companion watchapp over your phone's local Bluetooth connection.

The companion Pebble watchapp requests only the `Timeline` and `PebbleKit2` permissions required to authorize those local pin and AppMessage operations, and holds recent pin summaries only transiently in watch memory while displayed. No external servers or cloud relays are used.

## Android Permissions

- **Post Notifications (`POST_NOTIFICATIONS`, optional, Android 13+):** Used solely to display a local tap-to-open notification when you trigger **Open Phone App** from your Pebble watch while PebblePinManager is in the background.
- **Display Over Other Apps (`SYSTEM_ALERT_WINDOW`, optional, Android 10+):** Used only if you explicitly enable it in Android settings so **Open Phone App** on your watch can launch PebblePinManager immediately without an intermediate notification. It is never used to draw overlays over other apps or read screen content.

### Optional Notification Access (`NotificationListenerService`)
If and when optional Android **Notification Access** (`BIND_NOTIFICATION_LISTENER_SERVICE`) is available and explicitly enabled by you to automatically convert notifications from selected apps into Pebble Timeline pins:

- Notification Access is **off by default** and strictly opt-in, scoped to apps you choose.
- Notification content is read and processed **strictly on-device** solely to generate or update local Pebble Timeline pins for your paired watch.
- If you install a `-dev` or debug build and explicitly enable optional **Developer Mode**, up to the 100 most recent raw notifications are stored strictly on-device (`raw_notifications_dev_log`) so you can inspect notification fields and test regex rules interactively; you can clear this log or disable Developer Mode at any time. This log is explicitly excluded from Android Auto Backup and device-to-device transfer so captured notifications are never uploaded to Google Drive or copied to another device. Standard release builds do not include Developer Mode or raw notification capture.
- Notification data is **never transmitted off-device**, never uploaded to any server, and never shared with the developer or any third party.
- You can disable or revoke Notification Access at any time in your Android system settings.

## Data Retention and Deletion

All data remains under your control on your device:

- You can delete individual pins inside PebblePinManager (which removes them from local history and sends a delete request to your Pebble Timeline).
- You can erase all locally stored data at any time via **Android Settings → Apps → PebblePinManager → Storage & cache → Clear storage**, or by uninstalling the app.

## Third-Party Services

PebblePinManager does not integrate any third-party analytics, advertising, or cloud services. It interacts locally only with the Pebble companion app you have installed on your device (such as the Core Devices Pebble app or microPebble), which is governed by its own privacy policy.

## Children's Privacy

Because PebblePinManager collects no personal information from any user, it does not knowingly collect personal information from children.

## Open Source & Changes to This Policy

PebblePinManager is open-source software (MIT License), and its source code is publicly available for inspection at [github.com/gabrielhidasy/PebblePinManager](https://github.com/gabrielhidasy/PebblePinManager).

If this Privacy Policy changes (for example, when new optional features are added), the updated version will be published in `PRIVACY.md` in the repository with a revised effective date.

## Contact

If you have questions or concerns about this Privacy Policy, please open an issue on GitHub at [github.com/gabrielhidasy/PebblePinManager/issues](https://github.com/gabrielhidasy/PebblePinManager/issues).
