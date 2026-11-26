# Privacy Policy for Pin Manager for Pebble

**Effective Date:** November 25, 2026  
**Developer:** Gabriel Hidasy  
**Privacy Contact:** [gabrielhidasy+play@gmail.com](mailto:gabrielhidasy+play@gmail.com)  
**Hosted Privacy Policy:** [https://pebblepinmanager.hidasy.xyz/privacy](https://pebblepinmanager.hidasy.xyz/privacy)  
**Applies to:** Pin Manager for Pebble Android app (`xyz.hidasy.pebblepinmanager`) and the companion Pebble watchapp (`e4b7c3a1-8f2d-4c6e-9a51-7b3d2e8f1042`).

## Summary

Pin Manager for Pebble is designed to work **locally first** on your Android device and your paired Pebble smartwatch, with **strictly opt-in** network sync only for remote JSON feeds you explicitly configure:

- **Opt-in network access only:** The Android app declares the `INTERNET` and `ACCESS_NETWORK_STATE` permissions solely for the optional **Remote Pin Feeds** feature. Unless you explicitly add a Remote Pin Feed URL, the app makes **zero** network requests. When configured, requests only ever contact the exact URLs you enter (HTTPS required by default; optional per-feed HTTP toggle for local/LAN testing). The Pebble watchapp has no webview or PebbleKit JS component and makes no network requests.
- **No data collection or sharing:** Pin Manager for Pebble does not collect, transmit to the developer, sell, or share any personal information.
- **No telemetry, analytics, crash reporting, or ads:** The app contains no third-party analytics, tracking, crash-reporting, or advertising SDKs.

## Data Handled Locally on Your Device

### 1. Pin History, Remote Feed Configs & App Preferences
When you create, edit, or sync Timeline pins in Pin Manager for Pebble, the app stores your pin details (pin ID, title, optional description, event date/time, duration, icon, accent color, and local source metadata — up to the 100 most recent pins) strictly on your device using AndroidX Preferences DataStore (`sent_timeline_pins`). Basic UI preferences and non-secret Remote Pin Feed configurations (`remote_pin_feeds`, such as feed URLs, polling intervals, and ETag sync metadata) are also stored locally on your device.

If you have enabled Android's system-level device backup on your phone, Android Auto Backup (`android:allowBackup="true"`) may include your pin history (`sent_timeline_pins`) and non-secret app preferences in your personal device backup according to your Android system settings. Sensitive local stores — optional Developer Mode raw notification logs (`raw_notifications_dev_log`) and encrypted Remote Pin Feed authentication credentials (`remote_feed_secrets`) — are **explicitly excluded** from Android Auto Backup (cloud backup) and device-to-device transfer via `data_extraction_rules.xml` and `backup_rules.xml`. Pin Manager for Pebble itself does not access, transmit, or receive your backup data.

### 2. Optional Remote Pin Feeds (Opt-In Network Sync)
If you explicitly configure one or more **Remote Pin Feeds** in the **Feeds** tab:

- **User-controlled endpoints only:** Pin Manager for Pebble sends HTTP `GET` requests solely to the URL(s) you configure (for example, your own self-hosted companion server or automation JSON feed), using conditional caching headers (`If-None-Match` / `If-Modified-Since`) and the authentication method you select (`Bearer` token, custom API key header, HTTP Basic, or none).
- **HTTPS by default:** Plaintext `http://` URLs are rejected unless you explicitly enable the per-feed **"Allow HTTP (LAN testing)"** toggle. Authentication headers are stripped automatically on cross-origin redirects, and HTTPS-to-HTTP downgrades are blocked.
- **On-device encrypted secret storage:** Authentication tokens, API keys, and passwords are encrypted at rest on your device using **Android Keystore (AES-256-GCM)** inside a dedicated DataStore (`remote_feed_secrets`) that is excluded from Android Auto Backup and device-to-device transfer. Credentials are sent only to the exact feed origin you configured and are never logged or transmitted to the developer or any third party.
- **No outbound personal data:** The app only pulls pin JSON from your configured URL; it never uploads your manual pins, Auto-Pins, notifications, installed app list, or watch information to remote servers.

### 3. Local Communication with Your Paired Pebble Watch
Pin Manager for Pebble uses **PebbleKit Android 2** (`libpebble3`) local Android IPC to discover an installed Pebble companion app on your phone (such as the Core Devices Pebble app or microPebble), inspect connected watch status, insert/update/delete Timeline pins, and exchange AppMessages with the companion watchapp over your phone's local Bluetooth connection.

The companion Pebble watchapp requests only the `Timeline` and `PebbleKit2` permissions required to authorize those local pin and AppMessage operations, and holds recent pin summaries only transiently in watch memory while displayed. No external servers or cloud relays are operated by the developer.

### 4. Installed Launchable Apps List (Opt-In Auto-Pins Allowlist)
When you configure the opt-in **Auto-Pins** feature, Pin Manager for Pebble queries the list of launchable apps installed on your Android device (`ACTION_MAIN` / `CATEGORY_LAUNCHER` via Android's scoped `<queries>` package visibility — the app does **not** request `QUERY_ALL_PACKAGES`) so you can pick which apps are allowed to generate Pebble Timeline pins.

- This installed-app query runs **strictly locally on-device** solely to display app names and package identifiers in the Auto-Pins allowlist picker.
- Only the package names and rules for apps you explicitly choose to configure are stored locally in AndroidX DataStore on your device.
- Your list of installed apps is **never transmitted off-device**, never uploaded to any server, and never shared with the developer or any third party.

## Android Permissions

- **Internet & Network State (`INTERNET`, `ACCESS_NETWORK_STATE`, opt-in usage):** Used **strictly** when you explicitly configure a Remote Pin Feed URL (for manual **"Sync Now"** / **"Test Connection"** and optional periodic background polling via AndroidX WorkManager with a connected-network constraint). Unused and completely dormant if no remote feeds are configured.
- **Post Notifications (`POST_NOTIFICATIONS`, optional, Android 13+):** Used solely to display a local tap-to-open notification when you trigger **Open Phone App** from your Pebble watch while Pin Manager for Pebble is in the background.
- **Display Over Other Apps (`SYSTEM_ALERT_WINDOW`, optional, Android 10+):** Used only if you explicitly enable it in Android settings so **Open Phone App** on your watch can launch Pin Manager for Pebble immediately without an intermediate notification. It is never used to draw overlays over other apps or read screen content.

### Optional Notification Access (`NotificationListenerService`)
If and when optional Android **Notification Access** (`BIND_NOTIFICATION_LISTENER_SERVICE`) is available and explicitly enabled by you to automatically convert notifications from selected apps into Pebble Timeline pins:

- Notification Access is **off by default** and strictly opt-in, scoped to apps you choose.
- Notification content is read and processed **strictly on-device** solely to generate or update local Pebble Timeline pins for your paired watch.
- If you install a `-dev` or debug build and explicitly enable optional **Developer Mode**, up to the 100 most recent raw notifications are stored strictly on-device (`raw_notifications_dev_log`) so you can inspect notification fields and test regex rules interactively; you can clear this log or disable Developer Mode at any time. This log is explicitly excluded from Android Auto Backup and device-to-device transfer so captured notifications are never uploaded to Google Drive or copied to another device. Standard release builds do not include Developer Mode or raw notification capture.
- Notification data is **never transmitted off-device**, never uploaded to any server, and never shared with the developer or any third party.
- You can disable or revoke Notification Access at any time in your Android system settings.

## Data Retention and Deletion

All data remains under your control on your device:

- You can delete individual pins inside Pin Manager for Pebble (which removes them from local history and sends a delete request to your Pebble Timeline).
- You can erase all locally stored data at any time via **Android Settings → Apps → Pin Manager for Pebble → Storage & cache → Clear storage**, or by uninstalling the app.

## Third-Party Services

Pin Manager for Pebble does not integrate any third-party analytics, advertising, or cloud services. It interacts locally only with the Pebble companion app you have installed on your device (such as the Core Devices Pebble app or microPebble), which is governed by its own privacy policy.

## Children's Privacy

Because Pin Manager for Pebble collects no personal information from any user, it does not knowingly collect personal information from children.

## Open Source & Changes to This Policy

Pin Manager for Pebble is independent open-source software (MIT License) — not affiliated with, endorsed by, or sponsored by Core Devices or Pebble — and its source code is publicly available for inspection at [github.com/gabrielhidasy/PebblePinManager](https://github.com/gabrielhidasy/PebblePinManager).

The canonical hosted version of this Privacy Policy is published at [https://pebblepinmanager.hidasy.xyz/privacy](https://pebblepinmanager.hidasy.xyz/privacy) and maintained in [`PRIVACY.md`](https://github.com/gabrielhidasy/PebblePinManager/blob/main/PRIVACY.md) in the public repository. If this Privacy Policy changes (for example, when new optional features are added), the updated version will be published at that URL with a revised effective date.

## Contact

**Developer:** Gabriel Hidasy  
**Email:** [gabrielhidasy+play@gmail.com](mailto:gabrielhidasy+play@gmail.com)

If you have questions, privacy requests, or concerns about this Privacy Policy, you can contact the developer directly by email at [gabrielhidasy+play@gmail.com](mailto:gabrielhidasy+play@gmail.com) or open an issue on GitHub at [github.com/gabrielhidasy/PebblePinManager/issues](https://github.com/gabrielhidasy/PebblePinManager/issues).
