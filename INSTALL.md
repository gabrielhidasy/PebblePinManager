# Building, Sideloading, and Testing PebblePinManager

PebblePinManager has **two components** that work together with the **Core Devices Pebble Android app (`libpebble3`)**:
1. **Android App (`.apk`)** — native Kotlin + Jetpack Compose phone UI that creates and sends timeline pins via PebbleKit Android 2.
2. **Companion Pebble Watchapp (`.pbw`)** — lightweight watchapp compiled for **Pebble Time (`basalt`)** and **Pebble Time 2 (`emery`)** that registers UUID `e4b7c3a1-8f2d-4c6e-9a51-7b3d2e8f1042`, authorizes the Android packages (`xyz.hidasy.pebblepinmanager` & `xyz.hidasy.pebblepinmanager.debug`), and declares `Timeline` + `PebbleKit2` permissions for `libpebble3`.

---

## Option 1: Download Prebuilt Apps from GitHub Releases

1. Open [GitHub Releases](https://github.com/gabrielhidasy/PebblePinManager/releases) and select the latest release.
2. Download the Android APK and companion watchapp:
   - **`pebblepinmanager-android-<version>.apk`** — standard release build (without Developer Mode)
   - **`pebblepinmanager-android-<version>-dev.apk`** *(optional)* — release build with **Developer Mode** (adds the opt-in **Dev** tab for inspecting raw notifications and interactively exploring regex rules; shares the same package ID and release signing key so you can switch between standard and `-dev` in place without losing pins or rules)
   - **`pebblepinmanager-watchapp-<version>.pbw`**
3. Optionally verify integrity against `SHA256SUMS.txt`.

Then jump directly to [Installing Both Apps](#installing-both-apps).

---

## Option 2: Build Everything with Docker (No Local SDKs Required)

### One-Command Helper Script
```bash
./scripts/build-docker.sh          # builds both watchapp (.pbw) and Android app (.apk)
./scripts/build-docker.sh android  # builds Android APK + runs unit tests
./scripts/build-docker.sh watchapp # builds Pebble watchapp (.pbw) for basalt & emery
```
Outputs are placed in `dist/`:
- `dist/pebblepinmanager-android-debug.apk`
- `dist/pebblepinmanager-watchapp.pbw`

### Standalone Docker Commands

#### Build the Pebble Watchapp (`.pbw`)
```bash
docker build -f docker/Dockerfile.watchapp -t pebblepinmanager-watchapp-builder docker
docker run --rm -v "$PWD:/work" -w /work/watchapp pebblepinmanager-watchapp-builder pebble build
# Output: watchapp/build/watchapp.pbw
```

#### Test & Build the Android App (`.apk`)
```bash
docker build -f docker/Dockerfile.android -t pebblepinmanager-android-builder docker
docker run --rm -v "$PWD:/work" -w /work/android pebblepinmanager-android-builder \
  ./gradlew --no-daemon testDebugUnitTest assembleDebug
# Output: android/app/build/outputs/apk/debug/app-debug.apk
```

*(Tip: add `-v pebblepinmanager-gradle-cache:/root/.gradle` to the Android `docker run` command to cache Gradle downloads across builds.)*

---

## Option 3: Build Locally Without Docker

### Android App (`android/`)
Prerequisites: **JDK 17 or 21** and **Android SDK** (`platforms;android-37.0`, `build-tools;37.0.0`, `platform-tools`).

```bash
cd android
./gradlew testDebugUnitTest   # runs JVM unit tests
./gradlew assembleDebug       # outputs app/build/outputs/apk/debug/app-debug.apk
```

#### Release APK Signing (Optional)
To build signed release APKs (`xyz.hidasy.pebblepinmanager`) locally, point Gradle at your keystore via environment variables:
```bash
export ANDROID_RELEASE_KEYSTORE_PATH=/path/to/release.keystore
export ANDROID_RELEASE_KEYSTORE_PASSWORD=...
export ANDROID_RELEASE_KEY_ALIAS=...
export ANDROID_RELEASE_KEY_PASSWORD=...
cd android
./gradlew :app:assembleRelease :app:assembleReleaseDev
# Outputs:
#   android/app/build/outputs/apk/release/app-release.apk           (standard, without Developer Mode)
#   android/app/build/outputs/apk/releaseDev/app-releaseDev.apk     (-dev, with Developer Mode)
```

### Companion Watchapp (`watchapp/`)
Prerequisites: **Python 3.10+**, **Node.js**, and **`pebble-tool` (`5.2.0`)**.

```bash
uv tool install pebble-tool
pebble sdk install latest
cd watchapp
pebble build                  # outputs watchapp/build/watchapp.pbw
```

---

## Option 4: Dual-Emulator E2E Test & Usage Video (No Hardware Required)

You can test both the Android app (`.apk`) and companion Pebble watchapp (`.pbw`) end-to-end inside emulators and generate a side-by-side MP4 usage demo video:
- **PebbleKit2 Emulator Bridge (`android/emulator-bridge/` + `scripts/pebble-emulator-bridge.py`)** implements the `io.rebble.pebblekit2:server` ContentProvider + bound Service contract on Android so the unmodified Android APK forwards real PebbleKit2 `insertTimelinePin` / `deleteTimelinePin` IPC calls over `adb reverse` (`tcp:18442`) directly into the live Pebble QEMU Timeline.
- **Automated E2E Journey & Video Compositing (`scripts/run-emulator-e2e.py` + `scripts/compose-demo-video.py`)** drives the Android Compose UI via UI Automator, verifies live pin creation/editing/deletion on the Pebble emulator, and renders a 1280x720 H.264 MP4 video.

If you have an Android emulator running (via `adb devices`) plus `pebble`, `Xvfb`, and `ffmpeg` installed:
```bash
Xvfb :99 -screen 0 640x480x24 &
DISPLAY=:99 python3 scripts/run-emulator-e2e.py \
  --platforms basalt \
  --apk dist/pebblepinmanager-android-debug.apk \
  --bridge-apk dist/pebblepinmanager-emulator-bridge-debug.apk \
  --pbw dist/pebblepinmanager-watchapp.pbw \
  --output-dir artifacts
```

---

## Installing Both Apps

### Prerequisite on Your Android Phone
Install and pair your watch with a PebbleKit Android 2-compatible companion app:
- **Core Devices Pebble app** (`v1.0.7.7+`, powered by `libpebble3`), or
- **microPebble** (`v1.0.0-alpha35+`)

### Step 1: Install the Companion Watchapp (`.pbw`) onto Your Watch
Install the `.pbw` **first** so the Core Devices Pebble app registers the watchapp UUID and Android package permissions:

- **Easiest (Direct from Android phone):**
  1. Copy `pebblepinmanager-watchapp.pbw` (or `watchapp/build/watchapp.pbw`) to your Android phone.
  2. Tap the `.pbw` file in your Android file manager and choose **Open with Pebble** (Core Devices Pebble app).
  3. Confirm installation into your Pebble locker (approve the `Timeline` and `PebbleKit2` permissions if prompted).
- **Alternative (Over Wi-Fi via `pebble-tool`):**
  Enable **Developer Connection** in the Core Devices Pebble app and run:
  ```bash
  pebble install --phone <YOUR_PHONE_IP> watchapp/build/watchapp.pbw
  ```

### Step 2: Install the Android App (`.apk`) onto Your Phone
- **Option A — ADB (USB Debugging enabled):**
  ```bash
  adb devices
  adb install -r pebblepinmanager-android-<version>.apk
  ```
- **Option B — Direct APK Sideload:**
  Copy the `.apk` to your phone, tap it in your file manager, and allow installing unknown apps if prompted.

---

## Hardware Verification Checklist — Pebble Time (`basalt`) & Pebble Time 2 (`emery`)

Run through this checklist on physical watches after installing both `.pbw` and `.apk`:

### Common Pre-Checks
- [ ] Core Devices Pebble app (or microPebble) is installed and paired with the watch
- [ ] Companion watchapp `PebblePinManager` is installed in the Pebble locker and opens cleanly showing **"Phone: Connected / Ready for pins"**
- [ ] Launch **PebblePinManager** on Android; confirm the top status card reports **"Watch connected"**
- [ ] Disconnect/unavailable test: confirm the status card warns when the companion app is unavailable and **Send to Pebble Timeline** displays a clear inline error without losing entered text

### Original Pebble Time (`basalt`)
- [ ] Android status card identifies the watch as **`Pebble Time (basalt)`** with firmware version
- [ ] Create a pin with Title (`"Basalt Test Pin"`), multi-line Description, and time **+15 min**
- [ ] Tap **Send to Pebble Timeline** -> approve the first-time PebbleKit2 permission prompt in the Pebble app if shown -> confirm inline success banner and entry in **Recent Sent Pins**
- [ ] On the Pebble Time (`basalt`), press **Down** from the watchface to open the future timeline -> verify the pin appears at the expected local time with the correct title and full description on the color rectangular display
- [ ] Create a second pin in the past (e.g., 1 hour ago) and verify it appears under the **Up** (past) timeline

### Pebble Time 2 (`emery`)
- [ ] Android status card identifies the watch as **`Pebble Time 2 (emery)`** with firmware version
- [ ] Create a pin with Title (`"Emery Test Pin"`), multi-line Description, and time **+1 hour** (or **Tomorrow 09:00**)
- [ ] Tap **Send to Pebble Timeline** -> confirm inline success banner and entry in **Recent Sent Pins**
- [ ] On the Pebble Time 2 (`emery`), press **Down** to inspect the timeline -> verify the pin renders cleanly on the larger color rectangular display with crisp title, timestamp, and description body
- [ ] Verify timezone accuracy across local timezone display vs. watch display time
