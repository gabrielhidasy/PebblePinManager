# Pebble Pin Manager — Companion Server

A lightweight, zero-dependency Go HTTP server that stores Pebble Timeline pins durably and serves them as an authenticated, cache-friendly JSON feed for **Pin Manager for Pebble** (`#95`).

## Features

- **Built-in Hybrid Web UI (`/`)**: Self-contained, zero-dependency browser dashboard for creating, editing, and deleting pins (with local-timezone date/time picker, Pebble icons, and 64-color accent palette), viewing/scanning your pairing QR code, and retrieving or rotating API tokens for scripts. Supports both **standalone per-user write-token login** (`HttpOnly; SameSite=Strict` cookies + CSRF protection) and **reverse-proxy SSO** (e.g. Authelia `Remote-User` header) with automatic per-user provisioning.
- **Multi-User Isolation (`/{userId}/pins.json`)**: Each registered user receives a cryptographically random 32-char hex `userId`, an isolated pin feed (`GET /{userId}/pins.json`), isolated per-user CRUD endpoints (`/api/v1/users/{userId}/pins`), and dedicated per-user **read-only** and **read-write** tokens (authenticated via SHA-256 hashes; sealed at rest with AES-256-GCM so authenticated Web UI sessions can display QR codes and retrieve/rotate tokens without storing plaintext on disk).
- **Backward-Compatible Single-User Feed**: Legacy `GET /pins.json`, `GET /api/v1/pins.json`, and `/api/v1/pins` continue working seamlessly with global tokens.
- **Cheap Polling**: Strong `ETag` / `If-None-Match` (`304 Not Modified`), `Last-Modified` / `If-Modified-Since`, and `HEAD` support on both per-user and legacy feeds.
- **Scoped Token Authentication**: Constant-time SHA-256 token verification via `Authorization: Bearer <token>` or `X-API-Key: <token>`. Per-user tokens are strictly scoped to their own user ID (cross-user access returns `401` without leaking user existence); `PIN_SERVER_WRITE_TOKEN` also acts as an admin token for managing users. Raw tokens are never logged or stored in plaintext.
- **CRUD Management API**: Create (`POST`), upsert by stable ID (`PUT`), list (`GET`), inspect (`GET /{id}`), and delete (`DELETE`) pins with full `TimelineEvent` validation.
- **Automatic Retention**: Optionally expires pins whose end time (`startTime + durationMinutes`) is older than `PIN_SERVER_RETENTION` (default `48h`, matching Pebble Timeline's 2-day past window).
- **QR Code Registration Mode**: Opt-in `PIN_SERVER_REGISTRATION_MODE=true` enables `/register` (HTML form + `POST /register` HTML/JSON) that creates a new user with a random `userId`, renders an inline SVG QR code pointing to `/{userId}/pins.json` with the user's read-only token, and displays their one-time write token (disabled by default — returns `404` when off).
- **Durable Atomic Storage**: Persists user metadata (`users.json`) and per-user pin stores (`users/<userId>/pins.json`) via temp-file + `fsync` + atomic rename.
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
| `PIN_SERVER_DATA_FILE` | `/data/pins.json` | Path to the legacy single-user persistent JSON storage file. |
| `PIN_SERVER_DATA_DIR` | *(dir of `PIN_SERVER_DATA_FILE`)* | Directory storing multi-user registry (`users.json`) and per-user pin files (`users/<userId>/pins.json`). |
| `PIN_SERVER_MAX_USERS` | `1000` | Maximum number of registered multi-user accounts (`0` disables limit). |
| `PIN_SERVER_RETENTION` | `48h` | Go duration after a pin's end time before it expires from the feed. Set `0` to disable automatic expiration. |
| `PIN_SERVER_TRUST_PROXY` | `false` | If `true`, record client IP from `X-Forwarded-For` / `X-Real-IP` in access logs and respect `X-Forwarded-Proto` / `X-Forwarded-Host` on `/register`, Web UI, and user API responses. |
| `PIN_SERVER_WEB_UI` | `true` | Enable the self-contained Web UI at `/` (`false` disables HTML/UI routes for headless API-only deployments). |
| `PIN_SERVER_AUTH_PROXY_HEADER` | — | Optional trusted reverse-proxy identity header (e.g. `Remote-User` for Authelia / Authentik). Requires `PIN_SERVER_TRUST_PROXY=true`. Automatically maps each authenticated proxy user to a persistent isolated `<userId>` in the Web UI. |
| `PIN_SERVER_AUTH_PROXY_NAME_HEADER` | — | Optional reverse-proxy display name header (e.g. `Remote-Name`) used as the default feed label when auto-provisioning users via `PIN_SERVER_AUTH_PROXY_HEADER`. |
| `PIN_SERVER_TOKEN_ENCRYPTION_KEY` | — | Optional secret used to derive the AES-256-GCM key for sealing per-user tokens at rest (alias: `PIN_SERVER_TOKEN_ENCRYPTION_KEY_FILE`). When unset, a random 32-byte key is generated and persisted in `<dataDir>/.token-seal.key` (`0600`). |
| `PIN_SERVER_LOG_LEVEL` | `info` | Structured JSON log level: `debug`, `info`, `warn`, or `error`. |
| `PIN_SERVER_REGISTRATION_MODE` | `false` | When `true`, enables `GET`/`POST /register` to create new users with random IDs and pairing QR codes for `/{userId}/pins.json`. **Disable and restart after pairing.** |
| `PIN_SERVER_PUBLIC_URL` | — | Optional public base URL (e.g. `https://pins.example.com`) encoded in `/register` QR codes and user API URLs. Auto-detected from the incoming request when unset. |
| `PIN_SERVER_NAME` | — | Optional default friendly feed name (max 80 chars) pre-filled in `/register` and embedded in QR codes when no per-user name is supplied. |

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
| `GET` / `HEAD` `/` | Web UI (`PIN_SERVER_WEB_UI=true`) | Self-contained Web UI dashboard for adding/editing/deleting pins, scanning your pairing QR code, and retrieving/rotating API tokens. Signs in automatically via `PIN_SERVER_AUTH_PROXY_HEADER` (e.g. Authelia `Remote-User`) or via standalone write-token login. |
| `POST /ui/api/login`, `POST /ui/api/logout` | Web UI + CSRF | Standalone browser sign-in (accepts per-user write token or global admin write token) and sign-out (`HttpOnly; SameSite=Strict` session cookie). |
| `GET /ui/api/session`, `PUT /ui/api/profile` | Web UI Session or Auth Proxy | Fetch current Web UI session metadata & pairing QR code; update friendly feed display name. |
| `POST /ui/api/tokens/reveal`, `POST /ui/api/tokens/rotate` | Web UI Session or Auth Proxy + CSRF | Retrieve the authenticated user's read/write API tokens or rotate read and/or write tokens on demand. |
| `GET` / `POST` / `PUT` / `DELETE` `/ui/api/pins[/{id}]` | Web UI Session or Auth Proxy (+ CSRF on writes) | Browser CRUD endpoints scoped strictly to the signed-in user's pin store. |
| `GET` / `HEAD` `/register` | Registration Mode only | Renders the self-contained multi-user registration form (`404` when `PIN_SERVER_REGISTRATION_MODE=false`; redirects to `/#pair` when authenticated via `PIN_SERVER_AUTH_PROXY_HEADER`). |
| `POST /register` | Registration Mode only | Creates a new user with a random `userId` and scoped read/write tokens. Returns HTML with an inline SVG QR code (`pebblepin://pair?...` pointing to `/{userId}/pins.json`) for browser forms, or `201 Created` JSON when `Content-Type`/`Accept` is `application/json`. |
| `GET` / `HEAD` `/{userId}/pins.json` | User Read/Write or Admin | Authenticated per-user JSON pin feed with `ETag`, `Last-Modified`, `If-None-Match` (`304`), and `If-Modified-Since` (`304`). |
| `GET` / `HEAD` `/api/v1/users/{userId}/pins.json` | User Read/Write or Admin | Alias for `/{userId}/pins.json`. |
| `GET` / `HEAD` `/api/v1/users/{userId}/pins` | User Read/Write or Admin | List active pins for `{userId}` (`?includeExpired=true` includes expired pins not yet pruned). |
| `GET` / `HEAD` `/api/v1/users/{userId}/pins/{id}` | User Read/Write or Admin | Fetch a single active pin for `{userId}` (`404` if missing/expired). |
| `POST /api/v1/users/{userId}/pins` | User Write or Admin | Create a pin for `{userId}` (auto-generates `id` if omitted; returns `409` if `id` already exists). |
| `PUT /api/v1/users/{userId}/pins/{id}` | User Write or Admin | Create (`201`) or replace (`200`) a pin for `{userId}` by stable ID (upsert). |
| `DELETE /api/v1/users/{userId}/pins/{id}` | User Write or Admin | Delete a pin for `{userId}` (`204 No Content` or `404 Not Found`). |
| `GET` / `HEAD` `/api/v1/users` | Admin (`PIN_SERVER_WRITE_TOKEN`) | List all registered users (metadata & URLs only; never exposes token hashes). |
| `POST /api/v1/users` | Admin (`PIN_SERVER_WRITE_TOKEN`) | Programmatically register a new user (works even when `PIN_SERVER_REGISTRATION_MODE=false`) and return one-time `readToken`, `writeToken`, and `pairingUri`. |
| `GET` / `HEAD` `/api/v1/users/{userId}` | Admin (`PIN_SERVER_WRITE_TOKEN`) | Get metadata for a single registered user. |
| `DELETE /api/v1/users/{userId}` | Admin (`PIN_SERVER_WRITE_TOKEN`) | Delete a user and remove their isolated pin store (`204 No Content`). |
| `GET` / `HEAD` `/pins.json` | Global Read or Admin | Legacy single-user authenticated JSON pin feed. |
| `GET` / `HEAD` `/api/v1/pins.json` | Global Read or Admin | Alias for legacy `/pins.json`. |
| `GET` / `HEAD` `/api/v1/pins` | Global Read or Admin | List active legacy pins (`?includeExpired=true` supported). |
| `GET` / `HEAD` `/api/v1/pins/{id}` | Global Read or Admin | Fetch a single active legacy pin by ID. |
| `POST /api/v1/pins` | Admin (`PIN_SERVER_WRITE_TOKEN`) | Create a legacy pin. |
| `PUT /api/v1/pins/{id}` | Admin (`PIN_SERVER_WRITE_TOKEN`) | Upsert a legacy pin by stable ID. |
| `DELETE /api/v1/pins/{id}` | Admin (`PIN_SERVER_WRITE_TOKEN`) | Delete a legacy pin by ID. |

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

# 6. Delete a pin (legacy single-user)
curl -i -X DELETE "$BASE_URL/api/v1/pins/daily-standup" \
  -H "Authorization: Bearer $WRITE_TOKEN"

# 7. Multi-user: register a user (admin API, works even with registration mode off)
USER_JSON=$(curl -sf -X POST "$BASE_URL/api/v1/users" \
  -H "Authorization: Bearer $WRITE_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"Alice'\''s Pebble"}')
USER_ID=$(echo "$USER_JSON" | jq -r .id)
USER_WRITE_TOKEN=$(echo "$USER_JSON" | jq -r .writeToken)
USER_READ_TOKEN=$(echo "$USER_JSON" | jq -r .readToken)

# 8. Multi-user: push a pin to that user and fetch their isolated feed
curl -sf -X POST "$BASE_URL/api/v1/users/$USER_ID/pins" \
  -H "Authorization: Bearer $USER_WRITE_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"title":"Alice Standup","startTime":"2026-11-26T14:00:00Z"}'

curl -i "$BASE_URL/$USER_ID/pins.json" \
  -H "Authorization: Bearer $USER_READ_TOKEN"
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

#### Nginx / SWAG (with Authelia Multi-User Web UI)

To let Authelia handle browser authentication for the Web UI (`/`) while keeping mobile feeds (`/{userId}/pins.json`) and script APIs (`/api/v1/*`) directly token-authenticated, set `PIN_SERVER_TRUST_PROXY=true`, `PIN_SERVER_AUTH_PROXY_HEADER=Remote-User`, and `PIN_SERVER_AUTH_PROXY_NAME_HEADER=Remote-Name`, then split the proxy locations:

```nginx
server {
    listen 443 ssl http2;
    server_name pins.example.com;

    # ssl_certificate / ssl_certificate_key configured by certbot or SWAG
    include /config/nginx/authelia-server.conf;

    # Direct token-authenticated feed & CRUD endpoints (no Authelia redirect)
    location ~ ^/(healthz|pins\.json|api/v1/|[a-z0-9][a-z0-9_-]*/pins\.json)$ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Remote-User "";
        proxy_set_header Remote-Name "";
        proxy_set_header Cookie "";
    }

    # Browser Web UI & registration (Authelia SSO -> Remote-User auto-provisioning)
    location / {
        include /config/nginx/authelia-location.conf;
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Remote-User $user;
        proxy_set_header Remote-Name $name;
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
