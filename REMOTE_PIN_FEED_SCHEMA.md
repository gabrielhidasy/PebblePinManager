# Remote Pin Feed JSON Schema (v1)

This document defines the versioned JSON pin feed contract shared between:

- **Pin Manager for Pebble (Android client)** — Issue `#95`
- **PebblePinManager Companion Server** — Issue `#96`

## Overview

The Android app fetches a JSON document over **HTTPS** (HTTP is only allowed when explicitly enabled per feed for local LAN testing) using configured authentication headers and conditional caching (`ETag` / `If-None-Match`, `Last-Modified` / `If-Modified-Since`).

## HTTP Contract

| Aspect | Specification |
| :--- | :--- |
| **Method** | `GET` |
| **Accept header** | `application/json` |
| **Authentication** | One of: `Authorization: Bearer <token>`, custom header (default `X-API-Key: <token>`), `Authorization: Basic <base64(user:pass)>`, or none |
| **Conditional requests** | Client sends `If-None-Match` (when `ETag` was previously returned) and/or `If-Modified-Since` (when `Last-Modified` was previously returned) |
| **Not Modified** | Server responds with `304 Not Modified` (empty body); client keeps existing synced pins unchanged |
| **Auth failure** | Server responds with `401 Unauthorized` or `403 Forbidden`; client surfaces an error without deleting synced pins |
| **Max response size** | `2 MiB` (`2,097,152` bytes) enforced by Android client |

## JSON Schema (`schemaVersion = 1`)

### Example Payload

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
      "layoutType": "CALENDAR_PIN"
    }
  ]
}
```

### Root Object Fields

| Field | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `schemaVersion` | integer | **Yes** | Feed schema version. Must be `1`. Unknown versions are rejected without modifying previously synced pins. |
| `updatedAt` | string (ISO-8601) | No | Optional UTC timestamp indicating when the feed was last updated (informational). |
| `pins` | array of Pin objects | **Yes** | Complete current set of active pins for this feed. Must be a JSON array (may be empty `[]` to remove all previously synced pins from this feed). |

### Pin Object Fields

Every pin entry is validated against Pebble Timeline constraints (`TimelineEventValidator`). Invalid entries are skipped with per-entry diagnostics rather than aborting the entire sync.

| Field | Type | Required | Constraints / Default |
| :--- | :--- | :--- | :--- |
| `id` | string | **Yes** | Non-blank, trimmed, unique within the feed. Stable across syncs so updates apply in-place and removals delete the pin. |
| `title` | string | **Yes** | Non-blank after trimming, max `128` characters. |
| `description` | string \| null | No | Optional pin body, max `512` characters. Blank strings normalize to `null`. |
| `startTime` | string \| integer | **Yes*** | ISO-8601 timestamp with timezone (`2026-11-26T14:00:00Z` or offset `2026-11-26T11:00:00-03:00`) **or** UTC epoch milliseconds. Must fall within Pebble Timeline's rolling window (`-2 days` to `+365 days` from current time). |
| `startTimeMillis` | integer | No* | Alternative to `startTime` when providing UTC epoch milliseconds explicitly. At least one of `startTime` or `startTimeMillis` is required. |
| `durationMinutes` | integer \| null | No | Optional event duration in minutes, `1`–`1440` (inclusive). |
| `iconUri` | string \| null | No | Pebble system icon URI matching `^system://images/[A-Z0-9_]+$`. Defaults to `system://images/TIMELINE_CALENDAR`. |
| `backgroundColor` | string \| null | No | Optional 6-digit hex color (e.g. `#00AAFF` or `00AAFF`, normalized to `#RRGGBB`) for color Pebble watches. |
| `primaryColor` | string \| null | No | Optional 6-digit hex color for primary text/elements. |
| `secondaryColor` | string \| null | No | Optional 6-digit hex color for secondary text/elements. |
| `layoutType` | string \| null | No | One of `GENERIC_PIN` (default), `CALENDAR_PIN`, `GENERIC_REMINDER`, `GENERIC_NOTIFICATION` (case-insensitive). |

## Reconciliation Semantics

For a given configured feed (`source = REMOTE_URL`, `sourceKey = "remote:<feedId>"`):

1. **Insert**: Valid pins whose `id` has not yet been synced for this feed are inserted via `TimelinePinSender.sendPin` and recorded in `SentPinRepository`.
2. **In-place Update**: Valid pins whose `id` already exists for this feed are compared against the stored record; if any pin field changed, `TimelinePinSender.sendPin` is called with the same `id` to update the pin in-place. Unchanged pins are skipped.
3. **Delete**: Previously synced pins belonging to this feed whose `id` is no longer present in the feed's `pins` array are deleted via `TimelinePinSender.deletePin` and removed from `SentPinRepository`.
4. **Isolation & Collision Safety**:
   - Manual (`MANUAL`) and Notification Auto-Pin (`NOTIFICATION`) pins are never modified or deleted by remote feed sync.
   - If a remote pin `id` collides with an existing manual, auto-pin, or other feed's pin, the conflicting remote entry is skipped with a diagnostic.
   - If an existing remote pin `id` is still present in the feed but fails per-entry validation on a given sync, the update is skipped with a diagnostic instead of deleting the previously synced pin.
   - If the root payload is malformed, HTTP fails, or `schemaVersion` is unsupported, no pins are modified or deleted.
