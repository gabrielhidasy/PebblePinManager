# Google Play Reviewer Notes & App Access Instructions

Use the copy-paste block below in **Google Play Console → Policy and programs → App content → App access** (or in the **Notes for reviewer** / release submission comments when prompted).

---

## 1. Copy-Paste Summary for Play Console (App Access / Reviewer Notes)

```text
No login or account is required. Pin Manager for Pebble works offline by default (network access via INTERNET is strictly opt-in and dormant unless the user configures a Remote Pin Feed URL) and is normally paired locally with a Pebble smartwatch via PebbleKit Android 2.

HOW TO TEST WITHOUT PHYSICAL PEBBLE HARDWARE:
1. Launch the app. On the home "Pins" tab, the status card will report "Pebble companion app unavailable".
2. Tap "Run without a watch" on that status card (or tap the Settings gear icon in the top bar and toggle "Run without a watch" ON).
3. A new "Timeline" tab appears in the bottom navigation bar and the status card switches to "Running without a watch".
4. Manual Pins: Enter an Event Title (e.g., "Dentist appointment"), optional description, pick a quick time preset (e.g., "+15 min"), choose an icon/duration/accent color, and tap "Simulate Timeline Pin".
5. Simulated Timeline: Open the "Timeline" tab (or tap "Open Timeline") to inspect chronological pins, expand the generated PebbleKit 2 JSON payload, switch to "Sent Log", and test in-place "Edit" and "Delete" actions.
6. Auto-Pins (Optional): Open the "Auto-Pins" tab. You can test keyword/regex parsing immediately offline using the built-in "Rule Tester" card at the bottom without granting Notification Access. To verify the opt-in permission flow, tap "Open Notification Access Settings" to view the in-app Prominent Disclosure & consent modal (tapping Cancel keeps Notification Access disabled).

OPTIONAL PERMISSIONS JUSTIFICATION:
• SYSTEM_ALERT_WINDOW ("Display over other apps" / "Instant Open"):
  Disabled by default and NEVER required to use the app. This app NEVER draws floating windows or overlays over other apps (WindowManager.addView is not used anywhere). On Android 10+ (API 29+), it is used solely as Android's Background Activity Launch (BAL) exemption when a user explicitly triggers "Open Phone App" from their paired Pebble watch while the phone app is in the background. If not granted, the app gracefully falls back to a standard tap-to-open notification (POST_NOTIFICATIONS). Reviewers can inspect this in the top-bar bell icon ("Open from Pebble Watch").
• BIND_NOTIFICATION_LISTENER_SERVICE (Notification Access):
  Off by default and strictly opt-in, gated by an in-app Prominent Disclosure modal and scoped only to apps the user explicitly allowlists. Notification content is processed 100% on-device to build local Pebble Timeline pins and is never transmitted over the network (INTERNET is strictly isolated to user-configured Remote Pin Feed pull requests). Standard Play Store builds also compile out Developer Mode raw notification capture (BuildConfig.DEV_MODE_AVAILABLE = false).
```

---

## 2. Detailed Reviewer Walkthrough (Hardware-Free Verification)

### Step A — Enable Watchless Mode (`Run without a watch`)
1. Open **Pin Manager for Pebble**.
2. On a device without the Core Devices Pebble app or microPebble installed, the top status card displays **"Pebble companion app unavailable"** with an outlined **"Run without a watch"** button.
3. Tap **"Run without a watch"** (alternatively, tap the **Settings** gear icon in the top app bar and enable the **"Run without a watch"** switch).
4. Confirm:
   - The status card updates to **"Running without a watch"**.
   - The bottom navigation bar adds the **"Timeline"** tab alongside **"Pins"** and **"Auto-Pins"**.
   - The primary composer button changes from `"Send to Pebble Timeline"` to **`"Simulate Timeline Pin"`**.

### Step B — Manual Pin Creation, Editing & Deletion
1. On the **Pins** tab:
   - Enter an **Event Title** (required, e.g., `"Dentist appointment"`).
   - Optionally add a **Description** (up to 512 characters, e.g., `"Building B, 3rd floor"`).
   - Tap a quick time chip (**`Now`**, **`+15 min`**, **`+1 hour`**, or **`Tomorrow 09:00`**) or use the Date & Time pickers.
   - Select a **Pin Icon**, **Duration** preset, and **Accent Color**.
   - Tap **`Simulate Timeline Pin`**.
2. Confirm the green success banner appears and the new pin shows in **Recent Sent Pins**.
3. Tap **`Edit`** on the pin in **Recent Sent Pins**, update the title or time, and tap **`Update Simulated Pin`**.
4. Tap **`Delete`** on a pin and confirm in the dialog to verify removal from both **Recent Sent Pins** and the **Simulated Timeline**.

### Step C — Simulated Timeline Inspection
1. Tap the **Timeline** tab in the bottom bar (or **`Open Timeline`** on the status card).
2. Verify:
   - Active pins appear grouped chronologically.
   - Tapping a pin card expands the exact **PebbleKit 2** payload that would be delivered locally to a paired Pebble companion app.
   - Switching the filter chip from **`Timeline`** to **`Sent Log`** shows the full insert/update/delete audit trail.
   - Source filters (**`All`**, **`Manual`**, **`Auto-Pins`**) work locally, and in-place **`Edit`** / **`Delete`** actions stay in sync with the **Pins** tab.

### Step D — Optional Auto-Pins & Offline Rule Tester
1. Tap the **Auto-Pins** tab in the bottom bar.
2. Notice the subtitle **`100% On-Device • Opt-In Only`** and that Notification Access is **disabled by default**.
3. Scroll to the **Rule Tester** card at the bottom to test keyword filters, regex extraction rules, and event-time parsing completely offline without granting Notification Access.
4. Optionally tap **`Open Notification Access Settings`** to verify that the in-app **Prominent Disclosure & consent modal** (`#78`) appears before navigating to Android system settings (and that tapping **Cancel** stays inside the app).
5. Even if Notification Access is enabled on a test device, only apps explicitly checked in the **Allowed Apps** allowlist are processed, and in Watchless mode any matched notifications are captured locally in the **Simulated Timeline** tab.

### Step E — Watch Handoff Dialog (`POST_NOTIFICATIONS` & Optional `SYSTEM_ALERT_WINDOW`)
1. Tap the **Bell** icon in the top app bar on the **Pins** tab to open the **"Open from Pebble Watch"** dialog.
2. Verify both options are clearly labeled and optional:
   - **Tap-to-Open Notification** (`POST_NOTIFICATIONS`, Android 13+): posts a high-priority local notification when triggered from the watch.
   - **Instant Background Open** (`SYSTEM_ALERT_WINDOW`, Android 10+): labeled **Optional**; tapping **`Enable Instant Open`** navigates explicitly to Android's system `ACTION_MANAGE_OVERLAY_PERMISSION` screen for the user's voluntary opt-in.

---

## 3. Detailed Policy Justifications for Optional Permissions

### A. `android.permission.SYSTEM_ALERT_WINDOW` ("Display over other apps" / "Instant Open")

| Policy Question | Factual Implementation Detail |
| :--- | :--- |
| **Enabled by default?** | **No.** Always off by default; never blocks app usage or prompts in a loop. |
| **Required for core functionality?** | **No.** All manual pin creation/editing/deletion, Auto-Pins, Simulated Timeline, and watch sync features work with it denied. |
| **Does the app draw overlays over other apps?** | **Never.** The codebase contains zero `WindowManager.addView` calls, zero `TYPE_APPLICATION_OVERLAY` windows, and zero floating widgets/bubbles. |
| **Why is it declared?** | Android 10+ (API 29+) silently blocks `Context.startActivity()` from bound background services (`PebbleWatchListenerService`) under Background Activity Launch (BAL) restrictions. When a user explicitly presses **"Open Phone App"** (or long-presses Select) on their physically paired Pebble watch to compose a pin on their phone, Android permits an immediate direct activity launch only if the user has voluntarily granted `SYSTEM_ALERT_WINDOW` (Android's documented BAL exemption). |
| **Graceful fallback when not granted** | `PolicyPhoneAppLauncher.openPhoneUi()` checks `Settings.canDrawOverlays(appContext)`. When `false`, it skips `startMainActivity()` and posts a standard high-priority tap-to-open notification (`POST_NOTIFICATIONS`, channel `watch_open_phone`), informing the watch to display `"Tap phone notification"`. If notifications are also disabled, it reports `"Open app on phone"` gracefully without crashing or retrying. |
| **Auditable source references** | `android/app/src/main/AndroidManifest.xml`, `android/app/src/main/java/xyz/hidasy/pebblepinmanager/pebble/PhoneAppLauncher.kt`, `android/app/src/main/java/xyz/hidasy/pebblepinmanager/pebble/AndroidPhoneAppLauncher.kt` |

### B. `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE` (`PebbleNotificationListenerService`)

| Policy Question | Factual Implementation Detail |
| :--- | :--- |
| **Enabled by default?** | **No.** Strictly off by default; users must affirmatively agree to the in-app Prominent Disclosure modal (`#78`), enable Android Notification Access in system settings, enable Auto-Pins, and explicitly allowlist one or more source apps. |
| **Network transmission / third-party sharing?** | **Never.** All notification parsing happens 100% on-device (`NotificationPinPipeline` has zero network dependencies). `INTERNET` is used exclusively by the separate opt-in Remote Pin Feeds HTTP `GET` pull client (`remote/`) for URLs explicitly entered by the user; zero analytics/ad/telemetry SDKs exist anywhere in the app. |
| **On-device IPC scope** | Generated pins are sent locally via PebbleKit Android 2 (`io.rebble.pebblekit2:client`) exclusively to the user's installed Pebble companion app (Core Devices Pebble app or microPebble) — or to the local Simulated Timeline store when watchless mode is enabled. |
| **Release build hardening** | Standard Google Play `release` builds set `BuildConfig.DEV_MODE_AVAILABLE = false`, compiling out the Developer Mode raw notification inspector (`raw_notifications_dev_log`). In addition, `#79` excludes `raw_notifications_dev_log.preferences_pb` from Android Auto Backup and device transfer. |
| **Auditable source references** | `android/app/src/main/java/xyz/hidasy/pebblepinmanager/notifications/PebbleNotificationListenerService.kt`, `NotificationPinPipeline.kt`, `android/app/build.gradle.kts` |

### C. Package Visibility (`<queries>`, No `QUERY_ALL_PACKAGES`)

- The app does **not** request `QUERY_ALL_PACKAGES`.
- `AndroidManifest.xml` declares only two narrowly scoped `<queries>` intents:
  1. `io.rebble.pebblekit2.SEND_DATA_TO_WATCH` — required by PebbleKit Android 2 to discover an installed Pebble companion app.
  2. `android.intent.action.MAIN` + `android.intent.category.LAUNCHER` — used strictly locally on-device to populate the opt-in Auto-Pins app allowlist picker (`InstalledAppsProvider.kt`). The app list is never stored externally or transmitted off-device.
