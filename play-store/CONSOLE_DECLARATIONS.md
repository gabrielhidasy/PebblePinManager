# Google Play Console Declarations Checklist

Complete reference for every Play Console policy form and store setting for **Pin Manager for Pebble** (`xyz.hidasy.pebblepinmanager`).

---

## 1. Main Store Listing & Developer Details

| Play Console Field | Value | Notes |
| :--- | :--- | :--- |
| **App name** | `Pin Manager for Pebble` | 22 / 30 chars (`fastlane/metadata/android/en-US/title.txt`). Avoids leading with the `Pebble` trademark (`#74`). |
| **Short description** | `Add custom Timeline pins and on-device Auto-Pins to your Pebble smartwatch.` | 74 / 80 chars (`short_description.txt`). |
| **Full description** | Paste from `fastlane/metadata/android/en-US/full_description.txt` | Includes transparent permission disclosures + independent / non-affiliation disclaimer. |
| **App icon (512×512)** | `fastlane/metadata/android/en-US/images/icon.png` | 512×512 opaque PNG, no notification dot badge (`#75`). |
| **Feature graphic (1024×500)** | `fastlane/metadata/android/en-US/images/featureGraphic.png` | 1024×500 opaque 24-bit RGB PNG. |
| **Phone & Foldable screenshots** | Capture post-`#74`/`#78` via `scripts/capture-play-screenshots.py` | See [`SCREENSHOTS.md`](SCREENSHOTS.md). |
| **App category** | **Tools** (or **Productivity**) | Free utility companion app. |
| **Tags (optional)** | `Utilities`, `Smartwatch`, `Productivity` | Keep factual; avoid trademark stuffing. |
| **Developer name** | `Gabriel Hidasy` | Must match `PRIVACY.md` (`#76`). |
| **Contact email** | `gabrielhidasy+play@gmail.com` | Must match `PRIVACY.md` (`#76`). |
| **Website (optional)** | `https://github.com/gabrielhidasy/PebblePinManager` | Open-source repository (MIT License). |

---

## 2. Policy & Programs → App Content Declarations

### A. Privacy Policy
- [ ] **URL:** Enter the live static HTTPS Privacy Policy URL published in `#77` (must return HTTP `200`, be publicly accessible without login or geofencing, and render clean non-editable HTML — not a PDF or editable wiki).
- [ ] Verify the exact same Privacy Policy URL is accessible inside the app via **App Settings → Privacy Policy** (`#76`) and lists `Gabriel Hidasy` + `gabrielhidasy+play@gmail.com`.

### B. App Access
- [ ] **Selection:** *"All functionality in my app is available without any access restrictions"* — **OR**, if Play Console prompts for instructions for hardware-companion testing, select *"All or some functionality is restricted"* / add **App access instructions** and paste Section 1 of [`REVIEWER_NOTES.md`](REVIEWER_NOTES.md) directing reviewers to tap **"Run without a watch"** on the home status card.
  > **Recommendation:** Providing the copy-paste **Run without a watch** instructions proactively in App Access prevents reviewers without a physical Pebble watch from mistakenly flagging the app under *Minimum Functionality*.

### C. Ads
- [ ] **Does your app contain ads?** → **No, my app does not contain ads.**
  - Confirmed: zero ad SDKs, zero analytics/tracking SDKs, zero affiliate/promotional banners.

### D. Content Rating (IARC)
- [ ] **Category:** `Utility, Productivity, Communication, or Other`
- [ ] **Questionnaire answers:**
  - Violence / Fear / Sexual content / Profanity / Controlled substances / Gambling / Crude humor: **No** to all.
  - Does the app allow users to interact or exchange content with other users (UGC)? **No** (100% local single-device tool).
  - Does the app share the user's current physical location with other users? **No**.
  - Does the app allow users to purchase digital goods? **No**.
  - Is the app a web browser or search engine? **No**.
- [ ] **Expected rating:** **Everyone (ESRB) / PEGI 3 / USK 0 / L (ClassInd)**.

### E. Target Audience and Content
- [ ] **Target age group:** Select **13–15, 16–17, and 18 and over** (or **18 and over** only).
  - **Do NOT select any under-13 age bands** (`5 and under`, `6–8`, `9–12`) so Google Play's **Families Policy** requirements do not apply.
- [ ] **Could your store listing unintentionally appeal to children?** → **No** (utility companion for Pebble smartwatches; plain geometric icon and technical UI).

### F. Special App Categories
- [ ] **News apps:** **No**
- [ ] **COVID-19 contact tracing / status apps:** **No**
- [ ] **Health apps:** **No** (does not collect, track, or process health/fitness data)
- [ ] **Financial features:** **No**
- [ ] **Government apps:** **No**
- [ ] **AI-generated content:** **No**

---

## 3. Data Safety Form

> **Prerequisite:** Merge `#76` (updated `PRIVACY.md` + in-app link), `#78` (Prominent Disclosure & affirmative consent modal before Notification Access), and `#79` (exclude Dev Mode raw notification log from Auto Backup) before submitting this declaration.

| Data Safety Question | Answer | Rationale (Consistent with `PRIVACY.md` & Codebase) |
| :--- | :--- | :--- |
| **Does your app collect or share any of the required user data types?** | **No** | Zero analytics/telemetry/crash/ad SDKs and zero developer-operated servers. `INTERNET` / `ACCESS_NETWORK_STATE` are strictly opt-in for user-configured Remote Pin Feed URLs (HTTP `GET` pull only; auth secrets encrypted on-device with Android Keystore and excluded from Auto Backup). All manual pin history (`sent_timeline_pins`), preferences, and optional Auto-Pin processing occur 100% on-device. |
| **Data collected (transmitted off device)?** | **None** | Developer collects zero data. Optional Remote Pin Feeds only perform user-initiated `GET` pulls from user-entered HTTPS URLs and never upload personal data, notifications, or installed app lists. |
| **Data shared with third parties?** | **None** | Manual pins are user-initiated local IPC; opt-in Auto-Pins local IPC to the user's own Pebble companion app (`io.rebble.pebblekit2`) is strictly on-device and gated by the Prominent Disclosure & affirmative consent modal (`#78`), which qualifies as user-consented on-device transfer rather than third-party sharing under Google Play Data Safety policy. |
| **Is account creation supported?** | **No** | No accounts exist; Play's Account Deletion web URL requirement does not apply. |

**Play Store listing preview after completing Data Safety:**
- *"No data shared with third parties"*
- *"No data collected"*

---

## 4. Permissions & Restricted API Audit

| Manifest Entry / Capability | Play Console Declaration Form Required? | Status & Notes |
| :--- | :--- | :--- |
| `android.permission.INTERNET` & `ACCESS_NETWORK_STATE` | No | Standard permissions, **strictly opt-in** — dormant unless the user explicitly adds a Remote Pin Feed JSON URL (`#95`). HTTPS enforced by default; auth secrets encrypted via Android Keystore and excluded from Auto Backup. |
| `android.permission.CAMERA` (`android.hardware.camera` `required="false"`) | No | Optional runtime permission requested only if the user taps **"Scan QR"** to pair a companion pin server (`/register`). Frames are decoded strictly on-device in memory (CameraX + ZXing) and never saved or transmitted. |
| `android.permission.POST_NOTIFICATIONS` | No | Standard runtime permission (Android 13+), optional, used solely for watch handoff tap-to-open prompt. |
| `android.permission.SYSTEM_ALERT_WINDOW` | No | Special permission, optional, off by default, never draws overlays, used solely as Android 10+ BAL exemption for watch "Open Phone App". Covered in [`REVIEWER_NOTES.md`](REVIEWER_NOTES.md) & `full_description.txt`. |
| `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE` | No | User-granted system setting, off by default, gated by `#78` Prominent Disclosure modal, 100% on-device. |
| `<queries>` (`SEND_DATA_TO_WATCH`, `ACTION_MAIN`/`LAUNCHER`) | No | Scoped package visibility only — **`QUERY_ALL_PACKAGES` is NOT used**. Local-only installed-app query disclosed in `PRIVACY.md` (`#76`). |
| High-risk restricted APIs (SMS/Call Log, Location, Contacts, Accessibility, VPN, Exact Alarm, Full-Screen Intent, Foreground Service, Manage External Storage, Local Network, AppFunctions) | No | **None declared.** |

---

## 5. Trader Status Declaration (Nov 2026 Policy — Deadline: Feb 1, 2027)

Google Play's November 2026 policy update requires all developers to declare whether they act as a **Trader** or **Non-trader** (with enforcement deadline **February 1, 2027**):

- [ ] Navigate to **Play Console → Developer account → Account details / Trader status** (or the policy banner prompt).
- [ ] Select **Non-trader**.
  - **Justification:** `Pin Manager for Pebble` is a free, ad-free, open-source personal utility project (MIT License) with zero in-app purchases, zero subscriptions, zero paid distribution, and no commercial/trade activity.
- [ ] Review the EU Digital Services Act (DSA) notice shown by Play Console for non-trader accounts (Play displays a standard consumer-rights notice on EU listings stating that EU consumer-protection laws do not apply to non-trader accounts; no business address/phone disclosure is triggered as a commercial trader).

---

## 6. Build, Testing & Rollout Checklist

- [ ] **Target SDK:** `targetSdk = 36` (`compileSdk = 37`) already satisfies Google Play's Android 16 (API 36) mandate (effective Aug 31, 2026).
- [ ] **Signed `.aab` with R8 (`#81`):** Upload the signed `app-release.aab` built with `isMinifyEnabled = true` and `isShrinkResources = true` (satisfies Google Play's **Feb 2027** ≥25% R8 memory & code optimization threshold) plus the generated `mapping.txt` deobfuscation file.
- [ ] **Verify Play App Signing** is enrolled when creating the first release.
- [ ] **Closed Testing Gate (Personal accounts created on/after Nov 13, 2023):**
  - If publishing under a Personal account subject to the Closed Testing gate, run a Closed Testing track with **≥12 continuously opted-in testers for 14 consecutive days** (recruit 15–20 testers as a safety margin) and complete the Production Access questionnaire.
  - Organization accounts (with verified D-U-N-S) are exempt from the 12-tester / 14-day gate.
- [ ] **Watchapp Pairing (`#48`):** Ensure Play Store builds link to the Pebble App Store (`pebble://appstore/...`) rather than bundling/installing executable `.pbw` binaries.
