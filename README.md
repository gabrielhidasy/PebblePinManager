# Pin Manager for Pebble

Add custom events (**pins**) to your **Pebble Time (`basalt`)** and **Pebble Time 2 (`emery`)** smartwatch timeline directly from your Android phone.

> Independent open-source app — not affiliated with, endorsed by, or sponsored by Core Devices or Pebble.

## Overview

Pin Manager for Pebble consists of two cooperating components built for the modern **Core Devices Pebble Android app (`libpebble3`)** and **PebbleKit Android 2**:

1. **Native Android App (`android/` — Kotlin + Jetpack Compose + Material 3):**
   - Uses **PebbleKit Android 2** (`io.rebble.pebblekit2:client:1.3.2` -> `DefaultPebbleSender.insertTimelinePin` / `deleteTimelinePin`) for direct local timeline delivery — no external server or API keys required.
   - Enter an event **Title** (required), **Description** (optional, up to 512 chars), **Date & Time** in your local timezone (automatically converted to UTC for the Pebble Timeline), plus custom **Icon**, **Duration**, and **Accent Color**.
   - Displays live companion app & connected watch status (`Pebble Time (basalt)` / `Pebble Time 2 (emery)`), adapts cleanly to phones, foldables, and landscape layouts, persists sent pin history locally via AndroidX DataStore with in-place **Edit** and **Delete** actions, includes an opt-in on-device **Android Notification Access** (`PebbleNotificationListenerService`) pipeline (`#7`) with per-app allowlist, keyword/regex extraction, time parsing, and deduplicated pin updates, and supports opt-in **Remote JSON Pin Feeds** (`#95`, schema documented in [REMOTE_PIN_FEED_SCHEMA.md](REMOTE_PIN_FEED_SCHEMA.md)) with authenticated HTTPS polling, `ETag`/`304 Not Modified` caching, and `WorkManager` background sync.
2. **Companion Pebble Watchapp (`watchapp/` — C, targeting `aplite`, `basalt`, `chalk`, `diorite`, `emery`, `flint`, `gabbro`):**
   - Lightweight watchapp registered under UUID `e4b7c3a1-8f2d-4c6e-9a51-7b3d2e8f1042` declaring `companionApp.android.apps` (`xyz.hidasy.pebblepinmanager` & `xyz.hidasy.pebblepinmanager.debug`) and `usesPermissions` (`Timeline`, `PebbleKit2`) so Core Devices' `libpebble3` authorizes the Android app to insert/update/delete timeline pins and exchange AppMessages.
   - Displays a scrollable `MenuLayer` of recent sent pins synced from the Android companion app across monochrome, rectangular color, and round (`chalk` / `gabbro`) displays.
   - Supports on-watch **Reschedule** (`+15m`, `+30m`, `+1h`, `+3h`, `Tomorrow (+1d)`, `-15m`, `-1h`) and **Delete Pin** (with confirmation) via native `ActionMenu`, plus a `SELECT` shortcut (long-press `SELECT` anywhere, or `Open Phone App`) to launch the Android composer UI.

## Project Structure

```text
.
├── android/               # Native Android app (Kotlin + Jetpack Compose + PebbleKit Android 2)
│   ├── app/src/main/java/xyz/hidasy/pebblepinmanager/
│   │   ├── domain/        # TimelineEvent model, validation, UTC/ISO-8601 & timezone formatting
│   │   ├── pebble/        # TimelinePinSender abstraction, PebbleKit2PinSender, watch platform mapper
│   │   ├── data/          # SentPinRecord + DataStoreSentPinRepository local persistence
│   │   ├── notifications/ # NotificationPinPipeline & NotificationAccessStatusChecker
│   │   └── ui/            # Material 3 Compose PinComposerScreen & PinComposerViewModel
│   └── emulator-bridge/   # PebbleKit Android 2 server APK bridging Android emulator -> Pebble QEMU
├── watchapp/              # Companion Pebble watchapp (basalt & emery) authorizing PebbleKit 2 pins
├── server/                # Optional self-hosted Go companion server serving an authenticated JSON pin feed
├── docker/                # Reproducible Dockerfiles for Android APK & Pebble PBW builds
└── scripts/               # Docker build & dual-emulator E2E/video helper scripts
```

## Quick Start (Get or Build Both Apps)

- **Download Prebuilt Releases:** Grab the latest `.apk` and `.pbw` binaries from [GitHub Releases](https://github.com/gabrielhidasy/PebblePinManager/releases) (see [CHANGELOG.md](CHANGELOG.md) for what changed in each release).
- **Docker (Zero Local SDK Setup):**
  ```bash
  ./scripts/build-docker.sh
  # Outputs:
  #   dist/pebblepinmanager-android-debug.apk
  #   dist/pebblepinmanager-watchapp.pbw
  ```

See [INSTALL.md](INSTALL.md) for standalone Docker commands, local SDK builds, sideloading instructions for both `.apk` and `.pbw`, dual-emulator E2E testing, and the **Pebble Time (`basalt`) & Pebble Time 2 (`emery`)** verification checklist.

## Privacy

Pin Manager for Pebble works locally first on your phone and paired Pebble watch — network access is strictly opt-in (only contacting Remote Pin Feed URLs you explicitly configure, with auth secrets encrypted via Android Keystore and excluded from Auto Backup), and the app collects no telemetry or analytics and includes no ads or tracking SDKs. Read the full Privacy Policy at [pebblepinmanager.hidasy.xyz/privacy](https://pebblepinmanager.hidasy.xyz/privacy) (generated from [PRIVACY.md](PRIVACY.md)).

## Disclaimer

Independent open-source app — not affiliated with, endorsed by, or sponsored by Core Devices or Pebble.

## Contributing

Bug reports, feature requests, and pull requests are welcome on [GitHub](https://github.com/gabrielhidasy/PebblePinManager). See [CONTRIBUTING.md](CONTRIBUTING.md) for build and test instructions.
