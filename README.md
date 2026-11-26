# Pin Manager for Pebble

Add custom events (**pins**) to your **Pebble Time (`basalt`)** and **Pebble Time 2 (`emery`)** smartwatch timeline directly from your Android phone.

> Independent open-source app — not affiliated with, endorsed by, or sponsored by Core Devices or Pebble.

## Overview

Pin Manager for Pebble consists of two core components built for the modern **Core Devices Pebble Android app (`libpebble3`)** and **PebbleKit Android 2**, plus an optional self-hosted companion server:

1. **Native Android App (`android/` — Kotlin + Jetpack Compose + Material 3):**
   - Uses **PebbleKit Android 2** (`io.rebble.pebblekit2:client:1.3.2` -> `DefaultPebbleSender.insertTimelinePin` / `deleteTimelinePin`) for direct local timeline delivery — no external server or API keys required.
   - Enter an event **Title** (required), **Description** (optional, up to 512 chars), **Date & Time** in your local timezone (automatically converted to UTC for the Pebble Timeline), plus custom **Icon**, **Duration**, and **Accent Color**.
   - Displays live companion app & connected watch status (`Pebble Time (basalt)` / `Pebble Time 2 (emery)`), adapts cleanly to phones, foldables, and landscape layouts, persists sent pin history locally via AndroidX DataStore with in-place **Edit** and **Delete** actions, includes an opt-in on-device **Android Notification Access** (`PebbleNotificationListenerService`) pipeline (`#7`) with per-app allowlist, keyword/regex extraction, time parsing, and deduplicated pin updates, and supports opt-in **Remote JSON Pin Feeds** (`#95`, schema documented in [REMOTE_PIN_FEED_SCHEMA.md](REMOTE_PIN_FEED_SCHEMA.md)) with authenticated HTTPS polling, `ETag`/`304 Not Modified` caching, and `WorkManager` background sync.
2. **Companion Pebble Watchapp (`watchapp/` — C, targeting `aplite`, `basalt`, `chalk`, `diorite`, `emery`, `flint`, `gabbro`):**
   - Lightweight watchapp registered under UUID `e4b7c3a1-8f2d-4c6e-9a51-7b3d2e8f1042` declaring `companionApp.android.apps` (`xyz.hidasy.pebblepinmanager` & `xyz.hidasy.pebblepinmanager.debug`) and `usesPermissions` (`Timeline`, `PebbleKit2`) so Core Devices' `libpebble3` authorizes the Android app to insert/update/delete timeline pins and exchange AppMessages.
   - Displays a scrollable `MenuLayer` of recent sent pins synced from the Android companion app across monochrome, rectangular color, and round (`chalk` / `gabbro`) displays.
   - Supports on-watch **Reschedule** (`+15m`, `+30m`, `+1h`, `+3h`, `Tomorrow (+1d)`, `-15m`, `-1h`) and **Delete Pin** (with confirmation) via native `ActionMenu`, plus a `SELECT` shortcut (long-press `SELECT` anywhere, or `Open Phone App`) to launch the Android composer UI.
3. **Optional Companion JSON Pin Server (`server/` — Go + Docker):**
   - Lightweight, zero-dependency self-hosted HTTP server that stores timeline pins durably and serves them over an authenticated, `ETag`-cached JSON feed (`/pins.json`) so scripts, cron jobs, and webhooks can push pins to your Pebble watch.

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

## Self-Hosted JSON Pin Server (Optional)

If you want to push pins to your Pebble timeline from scripts, cron jobs, webhooks, or home-automation tools, `server/` provides a lightweight Go HTTP server that stores pins durably and serves an authenticated JSON feed (`GET /pins.json` / `GET /api/v1/pins.json`) consumed by the Android app.

### 1. Stand Up an Instance

#### Option A: Docker Compose (Recommended)

```bash
cd server
cp .env.example .env
```

Edit `server/.env` and replace the placeholder tokens with strong random values (generate each with `openssl rand -hex 32`):

- **`PIN_SERVER_WRITE_TOKEN`** *(required)* — Read-write token for scripts and automations (`POST` / `PUT` / `DELETE` and `GET`).
- **`PIN_SERVER_READ_TOKEN`** *(recommended)* — Separate read-only token for your Android phone (`GET` / `HEAD` only; returns `403 Forbidden` on write endpoints).

Start the container and verify it is healthy:

```bash
docker compose up -d --build
curl -sf http://127.0.0.1:8080/healthz
# {"status":"ok"}
```

#### Option B: Run Locally with Go (Go 1.22+)

```bash
cd server
PIN_SERVER_ADDR="127.0.0.1:8080" \
PIN_SERVER_DATA_FILE="./data/pins.json" \
PIN_SERVER_WRITE_TOKEN="$(openssl rand -hex 32)" \
PIN_SERVER_READ_TOKEN="$(openssl rand -hex 32)" \
go run ./cmd/pin-server
```

#### Expose Over HTTPS

`server/docker-compose.yml` binds strictly to `127.0.0.1:8080` so TLS is terminated by a reverse proxy (Caddy, Nginx/SWAG, Traefik, Tailscale Serve, etc.). The Android app enforces **HTTPS** by default. For example, with Caddy:

```caddyfile
pins.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

See [server/README.md](server/README.md) for Nginx/SWAG reverse-proxy examples, Docker secrets (`*_TOKEN_FILE`), retention settings (`PIN_SERVER_RETENTION`, default `48h`), and all environment variables.

### 2. Add, Update, and Delete Pins

Authenticate write requests with `Authorization: Bearer <PIN_SERVER_WRITE_TOKEN>` (or `X-API-Key: <PIN_SERVER_WRITE_TOKEN>`).

```bash
export BASE_URL="https://pins.example.com"
export WRITE_TOKEN="your-write-token"
export READ_TOKEN="your-read-token"

# Create a pin (server auto-generates an id when omitted)
curl -sf -X POST "$BASE_URL/api/v1/pins" \
  -H "Authorization: Bearer $WRITE_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "title": "Dentist Appointment",
    "description": "Suite 402",
    "startTime": "2026-11-26T15:30:00Z",
    "durationMinutes": 45,
    "iconUri": "system://images/TIMELINE_CALENDAR",
    "backgroundColor": "#00AAFF",
    "layoutType": "CALENDAR_PIN"
  }'

# Create or update (upsert) a pin with a stable ID (idempotent for cron jobs / webhooks)
curl -sf -X PUT "$BASE_URL/api/v1/pins/daily-standup" \
  -H "Authorization: Bearer $WRITE_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "title": "Team Standup",
    "description": "Daily sync in Room B",
    "startTime": "2026-11-26T14:00:00Z",
    "durationMinutes": 30,
    "layoutType": "CALENDAR_PIN"
  }'

# Delete a pin (removed from your watch on the next sync)
curl -sf -X DELETE "$BASE_URL/api/v1/pins/daily-standup" \
  -H "Authorization: Bearer $WRITE_TOKEN"

# Verify the live JSON feed using the read-only token
curl -sf "$BASE_URL/pins.json" \
  -H "Authorization: Bearer $READ_TOKEN"
```

- **Built-in Web UI (`/`):** Open `https://pins.example.com/` in a browser to add, edit, and delete pins interactively, view/scan your pairing QR code, and retrieve or rotate your per-user API tokens. Supports both standalone write-token sign-in and reverse-proxy SSO (e.g. Authelia `PIN_SERVER_AUTH_PROXY_HEADER=Remote-User`) with automatic per-user provisioning.
- **Required fields:** `title` (1–128 chars) and `startTime` (RFC3339/ISO-8601 timestamp with timezone, within 2 days in the past to 365 days in the future).
- **Optional fields:** `id` (auto-generated on `POST`), `description` (up to 512 chars), `durationMinutes` (`1`–`1440`), `iconUri` (`system://images/<ICON_NAME>`), `backgroundColor` / `primaryColor` / `secondaryColor` (`#RRGGBB`), and `layoutType` (`GENERIC_PIN`, `CALENDAR_PIN`, `GENERIC_REMINDER`, `GENERIC_NOTIFICATION`).
- See [server/README.md](server/README.md) and [REMOTE_PIN_FEED_SCHEMA.md](REMOTE_PIN_FEED_SCHEMA.md) for the full API reference and JSON feed schema.

### 3. Pair with Your Pebble Watch

Pins flow from **JSON Server -> Pin Manager for Pebble (Android) -> Pebble Watch Timeline** via PebbleKit Android 2:

1. **Connect your watch to Pin Manager for Pebble:**
   - Ensure your Pebble watch is paired with the **Core Devices Pebble app** (or **microPebble**) and that both the companion watchapp (`.pbw`) and Android app (`.apk`) are installed (see [INSTALL.md](INSTALL.md#installing-both-apps)).
   - Open **Pin Manager for Pebble** on your phone and verify the status card on the **Pins** tab shows your watch as connected.
2. **Pair your server in the Android app (QR code or manual setup):**
   - **Instant QR code pairing & multi-user registration (recommended):** Temporarily set `PIN_SERVER_REGISTRATION_MODE=true` (and optionally `PIN_SERVER_PUBLIC_URL=https://pins.example.com` and `PIN_SERVER_NAME="Home Pin Server"`), restart the server, and open `https://pins.example.com/register` in a browser. Click **Create User & Generate Pairing QR** to mint an isolated user feed at `https://pins.example.com/<random-id>/pins.json` with dedicated per-user read and write tokens. In **Pin Manager for Pebble**, open the **Feeds** tab, tap **Scan QR**, and point your camera at the QR code to pair and run an initial sync automatically. Save the displayed one-time Write API Token for pushing pins to `/api/v1/users/<random-id>/pins`, then set `PIN_SERVER_REGISTRATION_MODE=false` and restart the server. (Admins can also create/list/delete users anytime via `/api/v1/users` with `PIN_SERVER_WRITE_TOKEN`.)
   - **Manual setup:** Open the **Feeds** tab, tap **Add Feed** (or `+`), enter your **Remote JSON URL** (`https://pins.example.com/pins.json`), select **Bearer Token** (or **API Key Header** with `X-API-Key`), paste your **`PIN_SERVER_READ_TOKEN`**, choose a **Background Polling Interval**, tap **Test Connection**, and tap **Save**.
     - *Local LAN testing only:* if you are testing without TLS on a trusted local network, toggle **Allow HTTP (LAN testing)** and use an `http://...` URL (HTTP QR codes also prompt for explicit confirmation before saving).
     - Credentials are encrypted on-device with Android Keystore and excluded from Android Auto Backup.
3. **Sync pins to the watch timeline:**
   - Tap **Sync Now** on the feed card (or wait for the next `WorkManager` background poll).
   - The app polls with `ETag` / `If-None-Match` (`304 Not Modified` when nothing changed) and reconciles pins onto your paired watch: new pin IDs are inserted, modified pins are updated in place, and pins deleted or expired on the server are removed from the watch timeline (manual pins and Auto-Pins are never affected).
   - Press **Down** (future) or **Up** (past) on your Pebble watch to view your synced pins.

## Privacy

Pin Manager for Pebble works locally first on your phone and paired Pebble watch — network access is strictly opt-in (only contacting Remote Pin Feed URLs you explicitly configure, with auth secrets encrypted via Android Keystore and excluded from Auto Backup), and the app collects no telemetry or analytics and includes no ads or tracking SDKs. Read the full Privacy Policy at [pebblepinmanager.hidasy.xyz/privacy](https://pebblepinmanager.hidasy.xyz/privacy) (generated from [PRIVACY.md](PRIVACY.md)).

## Disclaimer

Independent open-source app — not affiliated with, endorsed by, or sponsored by Core Devices or Pebble.

## Contributing

Bug reports, feature requests, and pull requests are welcome on [GitHub](https://github.com/gabrielhidasy/PebblePinManager). See [CONTRIBUTING.md](CONTRIBUTING.md) for build and test instructions.
