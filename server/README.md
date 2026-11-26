# Pebble Pin Manager — Companion Server

A lightweight, zero-dependency Go HTTP server that stores Pebble Timeline pins durably and serves them as an authenticated, cache-friendly JSON feed for **Pin Manager for Pebble** (`#95`).

## Features

- **Authenticated JSON Feed**: Serves `GET /pins.json` and `GET /api/v1/pins.json` using the shared v1 JSON pin schema.
- **Cheap Polling**: Strong `ETag` / `If-None-Match` (`304 Not Modified`), `Last-Modified` / `If-Modified-Since`, and `HEAD` support.
- **Scoped Token Authentication**: Constant-time SHA-256 token verification via `Authorization: Bearer <token>` or `X-API-Key: <token>`, supporting optional separate **read-only** (phone) and **read-write** (automation/scripts) tokens. Raw tokens are never logged.
- **CRUD Management API**: Create (`POST`), upsert by stable ID (`PUT`), list (`GET`), inspect (`GET /{id}`), and delete (`DELETE`) pins with full `TimelineEvent` validation.
- **Automatic Retention**: Optionally expires pins whose end time (`startTime + durationMinutes`) is older than `PIN_SERVER_RETENTION` (default `48h`, matching Pebble Timeline's 2-day past window).
- **QR Code Registration Mode**: Opt-in `PIN_SERVER_REGISTRATION_MODE=true` enables a self-contained `/register` HTML page with an inline SVG QR code for instant pairing with the Android app (disabled by default — returns `404` when off).
- **Durable Atomic Storage**: Persists pins to a single JSON file via temp-file + `fsync` + atomic rename.
- **Containerized**: Static scratch Docker image running as non-root (`65532:65532`) with a built-in `-healthcheck` probe and hardened `docker-compose.yml`.

---

## Shared JSON Feed Schema (`schemaVersion: 1`)

```json
{
  "schemaVersion": 1,
  "updatedAt": "2026-11-25T22:00:00Z",
  "pins": [
    {
      "id": "ext-meeting-123",
      "title": "Team Standup",
      "description": "Daily sync in Room B",
      "startTime": "2026-11-26T14:00:00Z",
      "durationMinutes": 30,
      "iconUri": "system://images/TIMELINE_CALENDAR",
      "backgroundColor": "#00AAFF",
      "primaryColor": "#FFFFFF",
      "secondaryColor": "#AAAAAA",
      "layoutType": "CALENDAR_PIN"
    }
  ]
}
```

### Pin Field Constraints

Aligned with `TimelineEvent` & `TimelineEventValidator` in the Android app:

| Field | Required | Constraints / Default |
|---|---|---|
| `id` | On `PUT` path; optional on `POST` | 1–128 chars matching `^[A-Za-z0-9._:@-]+$`. Auto-generated as `ppm-<12-hex>` when omitted on `POST`. |
| `title` | Yes | 1–128 characters after trimming. |
| `description` | No | Up to 512 characters after trimming; blank strings are omitted. |
| `startTime` | Yes | Valid ISO-8601 / RFC3339 timestamp with timezone; normalized to UTC. Must be within 2 days in the past to 365 days in the future. |
| `durationMinutes` | No | Integer `1`–`1440` (minutes). |
| `iconUri` | No | Must match `^system://images/[A-Z0-9_]+$`. Defaults to `system://images/TIMELINE_CALENDAR`. |
| `backgroundColor` | No | 6-digit hex color (`#RRGGBB` or `RRGGBB`), normalized to uppercase `#RRGGBB`. |
| `primaryColor` | No | 6-digit hex color, normalized to uppercase `#RRGGBB`. |
| `secondaryColor` | No | 6-digit hex color, normalized to uppercase `#RRGGBB`. |
| `layoutType` | No | One of `GENERIC_PIN` (default), `CALENDAR_PIN`, `GENERIC_REMINDER`, `GENERIC_NOTIFICATION`. |

Unknown JSON fields are rejected with `400 Bad Request`.

---

## Configuration (Environment Variables)

| Variable | Default | Description |
|---|---|---|
| `PIN_SERVER_ADDR` | `:8080` | Bind address (`host:port`). |
| `PIN_SERVER_WRITE_TOKEN` | *(required\*)* | Read-write API token (supports `POST`/`PUT`/`DELETE` and `GET`/`HEAD`). Alias: `PIN_SERVER_TOKEN`. |
| `PIN_SERVER_WRITE_TOKEN_FILE` | — | Path to file containing the write token (for Docker secrets). Alias: `PIN_SERVER_TOKEN_FILE`. |
| `PIN_SERVER_READ_TOKEN` | — | Optional read-only token (`GET`/`HEAD` only; returns `403` on writes). Ideal for configuring on your phone. |
| `PIN_SERVER_READ_TOKEN_FILE` | — | Path to file containing the read-only token. |
| `PIN_SERVER_DATA_FILE` | `/data/pins.json` | Path to the persistent JSON storage file. |
| `PIN_SERVER_RETENTION` | `48h` | Go duration after a pin's end time before it expires from the feed. Set `0` to disable automatic expiration. |
| `PIN_SERVER_TRUST_PROXY` | `false` | If `true`, record client IP from `X-Forwarded-For` / `X-Real-IP` in access logs and respect `X-Forwarded-Proto` / `X-Forwarded-Host` on `/register`. |
| `PIN_SERVER_LOG_LEVEL` | `info` | Structured JSON log level: `debug`, `info`, `warn`, or `error`. |
| `PIN_SERVER_REGISTRATION_MODE` | `false` | When `true`, enables `GET /register` showing a pairing QR code for the Android app. **Disable and restart after pairing.** |
| `PIN_SERVER_PUBLIC_URL` | — | Optional public base URL (e.g. `https://pins.example.com`) encoded in `/register` QR codes. Auto-detected from the incoming request when unset. |
| `PIN_SERVER_NAME` | — | Optional friendly feed name (max 80 chars) embedded in `/register` QR codes. |

\* At least one of `PIN_SERVER_WRITE_TOKEN` (or `PIN_SERVER_TOKEN`) or `PIN_SERVER_READ_TOKEN` must be configured; the server refuses to start unauthenticated.

Generate strong tokens with:

```bash
openssl rand -hex 32
```

---

## API Reference

| Method & Path | Auth Role | Description |
|---|---|---|
| `GET` / `HEAD` `/healthz` | None | Liveness probe returning `{"status":"ok"}`. |
| `GET` / `HEAD` `/register` | Registration Mode only | Self-contained HTML page with QR code (`pebblepin://pair?...`) for instant Android pairing. Only registered when `PIN_SERVER_REGISTRATION_MODE=true` (returns `404` otherwise). Embeds `PIN_SERVER_READ_TOKEN` when configured (falling back to write token with a warning). |
| `GET` / `HEAD` `/pins.json` | Read or Write | Authenticated JSON pin feed with `ETag`, `Last-Modified`, `If-None-Match` (`304`), and `If-Modified-Since` (`304`). |
| `GET` / `HEAD` `/api/v1/pins.json` | Read or Write | Alias for `/pins.json`. |
| `GET` / `HEAD` `/api/v1/pins` | Read or Write | List active pins (`?includeExpired=true` includes expired pins not yet pruned). |
| `GET` / `HEAD` `/api/v1/pins/{id}` | Read or Write | Fetch a single active pin by ID (`404` if missing/expired). |
| `POST /api/v1/pins` | Write | Create a pin (auto-generates `id` if omitted; returns `409` if `id` already exists). |
| `PUT /api/v1/pins/{id}` | Write | Create (`201`) or replace (`200`) a pin by stable ID (upsert). |
| `DELETE /api/v1/pins/{id}` | Write | Delete a pin by ID (`204 No Content` or `404 Not Found`). |

Authentication headers accepted on all protected routes:
- `Authorization: Bearer <token>`
- `X-API-Key: <token>`

Error status codes:
- `401 Unauthorized` — missing, malformed, or invalid token
- `403 Forbidden` — valid read-only token used on a write endpoint
- `400 Bad Request` — invalid JSON, unknown field, ID mismatch, or validation failure (includes structured `details`)
- `404 Not Found` — pin does not exist (or has expired)
- `409 Conflict` — `POST /api/v1/pins` with an `id` that already exists

---

## `curl` Usage Examples

```bash
export BASE_URL="https://pins.example.com"
export WRITE_TOKEN="your-write-token"
export READ_TOKEN="your-read-token"

# 1. Health check (unauthenticated)
curl -sf "$BASE_URL/healthz"

# 2. Create a pin (auto-generated ID)
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

# 3. Upsert a pin with a stable ID (idempotent for cron jobs / webhooks)
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

# 4. Fetch authenticated feed with ETag (as the Android app does)
curl -i "$BASE_URL/pins.json" \
  -H "Authorization: Bearer $READ_TOKEN"

# 5. Conditional poll using the ETag returned above -> 304 Not Modified when unchanged
curl -i "$BASE_URL/pins.json" \
  -H "Authorization: Bearer $READ_TOKEN" \
  -H 'If-None-Match: "<etag-from-step-4>"'

# 6. Delete a pin
curl -i -X DELETE "$BASE_URL/api/v1/pins/daily-standup" \
  -H "Authorization: Bearer $WRITE_TOKEN"
```

---

## Deployment

### Docker Compose

```bash
cd server
cp .env.example .env
# Edit .env and set strong random tokens (openssl rand -hex 32)
docker compose up -d --build
```

The sample `docker-compose.yml` binds strictly to `127.0.0.1:8080` so TLS is terminated cleanly by a reverse proxy.

### Reverse Proxy HTTPS Examples

#### Caddy

```caddyfile
pins.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

#### Nginx / SWAG

```nginx
server {
    listen 443 ssl http2;
    server_name pins.example.com;

    # ssl_certificate / ssl_certificate_key configured by certbot or SWAG

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

---

## Local Development & Testing

Requires Go 1.22+:

```bash
cd server

# Run unit and HTTP integration tests with race detector
go vet ./...
go test -race -v ./...

# Run locally
PIN_SERVER_ADDR="127.0.0.1:8080" \
PIN_SERVER_DATA_FILE="./data/pins.json" \
PIN_SERVER_WRITE_TOKEN="$(openssl rand -hex 32)" \
PIN_SERVER_READ_TOKEN="$(openssl rand -hex 32)" \
go run ./cmd/pin-server
```
