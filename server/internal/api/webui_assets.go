package api

import "html/template"

type webUIIndexData struct {
	CSRFToken  string
	ServerName string
}

var webUIIndexTemplate = template.Must(template.New("webui-index").Parse(`<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="robots" content="noindex,nofollow">
  <meta name="csrf-token" content="{{.CSRFToken}}">
  <title>{{if .ServerName}}{{.ServerName}} — {{end}}Pin Manager for Pebble</title>
  <link rel="stylesheet" href="/ui/style.css">
</head>
<body>
  <div class="app-shell">
    <header class="topbar">
      <div class="brand">
        <h1>Pin Manager for Pebble</h1>
        <p id="server-subtitle" class="subtitle">{{if .ServerName}}{{.ServerName}}{{else}}Companion Pin Server{{end}}</p>
      </div>
      <div class="topbar-actions">
        <span id="auth-badge" class="badge hidden"></span>
        <button id="logout-btn" class="btn btn-secondary hidden" type="button">Sign Out</button>
      </div>
    </header>

    <div id="global-alert" class="alert hidden" role="status"></div>

    <section id="loading-view" class="card centered">
      <p class="muted">Loading session...</p>
    </section>

    <section id="login-view" class="card auth-card hidden">
      <h2>Sign In</h2>
      <p class="muted">Enter your per-user <strong>Write API Token</strong> (or admin write token for the legacy feed) to add, edit, and delete Pebble Timeline pins.</p>
      <form id="login-form">
        <div class="field">
          <label for="login-token">Write API Token</label>
          <input id="login-token" name="token" type="password" autocomplete="current-password" required placeholder="Paste your write token">
        </div>
        <div id="login-error" class="alert alert-error hidden" role="alert"></div>
        <div class="btn-row">
          <button id="login-submit" class="btn btn-primary" type="submit">Sign In</button>
          <a id="login-register-link" class="btn btn-secondary hidden" href="/register">Register New User</a>
        </div>
      </form>
      <p id="login-proxy-hint" class="hint hidden">Reverse-proxy SSO is enabled on this server. Access via your authenticated proxy URL to sign in automatically without a token.</p>
    </section>

    <div id="dashboard-view" class="dashboard-grid hidden">
      <div class="primary-col">
        <section class="card" aria-labelledby="composer-heading">
          <div class="card-header">
            <h2 id="composer-heading">Add Pin</h2>
            <button id="cancel-edit-btn" class="btn btn-secondary btn-sm hidden" type="button">Cancel Edit</button>
          </div>
          <form id="pin-form">
            <div class="field">
              <div class="label-row">
                <label for="pin-title">Title *</label>
                <span id="pin-title-count" class="char-count">0 / 128</span>
              </div>
              <input id="pin-title" name="title" type="text" maxlength="128" required placeholder="e.g. Dentist Appointment">
            </div>

            <div class="field-row">
              <div class="field">
                <label for="pin-start">Date &amp; Time (Local) *</label>
                <input id="pin-start" name="startTime" type="datetime-local" required>
              </div>
              <div class="field">
                <label for="pin-duration">Duration (minutes)</label>
                <input id="pin-duration" name="durationMinutes" type="number" min="1" max="1440" placeholder="Optional">
                <div class="chip-row" id="duration-presets">
                  <button class="chip" type="button" data-duration="">None</button>
                  <button class="chip" type="button" data-duration="15">15m</button>
                  <button class="chip" type="button" data-duration="30">30m</button>
                  <button class="chip" type="button" data-duration="60">1h</button>
                  <button class="chip" type="button" data-duration="120">2h</button>
                </div>
              </div>
            </div>

            <div class="field">
              <div class="label-row">
                <label for="pin-description">Description</label>
                <span id="pin-description-count" class="char-count">0 / 512</span>
              </div>
              <textarea id="pin-description" name="description" rows="3" maxlength="512" placeholder="Optional notes, address, or agenda"></textarea>
            </div>

            <div class="field">
              <div class="label-row">
                <label id="pin-icon-label">Icon</label>
                <span id="pin-icon-selected-name" class="char-count">Calendar</span>
              </div>
              <div id="icon-picker" class="icon-grid" role="radiogroup" aria-labelledby="pin-icon-label"></div>
              <input id="pin-icon" name="iconUri" type="hidden" value="system://images/TIMELINE_CALENDAR">
            </div>

            <div class="field">
              <label>Accent Color</label>
              <div id="color-swatches" class="swatch-row" role="radiogroup" aria-label="Pin accent color"></div>
              <input id="pin-color" type="hidden" value="">
            </div>

            <details class="advanced-section">
              <summary>Advanced (Layout &amp; Custom Pin ID)</summary>
              <div class="field">
                <label for="pin-layout">Layout</label>
                <select id="pin-layout" name="layoutType">
                  <option value="GENERIC_PIN">Generic Pin (default)</option>
                  <option value="CALENDAR_PIN">Calendar Pin</option>
                </select>
                <p class="hint">Pebble Timeline pin template (Generic Pin or Calendar Pin). Defaults to Generic Pin, matching the Android pin builder.</p>
              </div>
              <div class="field">
                <label for="pin-id">Pin ID (optional)</label>
                <input id="pin-id" name="id" type="text" maxlength="128" placeholder="Auto-generated (e.g. ppm-a1b2c3d4e5f6)">
                <p class="hint">Allowed characters: letters, digits, <code>.</code> <code>_</code> <code>:</code> <code>@</code> <code>-</code>. Useful for stable script/webhook pin IDs.</p>
              </div>
            </details>

            <div id="pin-form-error" class="alert alert-error hidden" role="alert"></div>

            <div class="btn-row">
              <button id="pin-submit-btn" class="btn btn-primary" type="submit">Add Pin</button>
              <button id="pin-reset-btn" class="btn btn-secondary" type="button">Reset</button>
            </div>
          </form>
        </section>

        <section class="card" aria-labelledby="pins-heading">
          <div class="card-header">
            <div class="header-title-group">
              <h2 id="pins-heading">Timeline Pins</h2>
              <span id="pins-count" class="badge">0</span>
            </div>
            <div class="header-controls">
              <label class="checkbox-label">
                <input id="include-expired" type="checkbox">
                Show expired
              </label>
              <button id="refresh-pins-btn" class="btn btn-secondary btn-sm" type="button">Refresh</button>
            </div>
          </div>
          <div id="pins-list" class="pins-list"></div>
        </section>
      </div>

      <aside class="side-col">
        <section id="pair" class="card hidden" aria-labelledby="pair-heading">
          <h2 id="pair-heading">Pair Pebble App</h2>
          <p class="muted">Scan in <strong>Pin Manager for Pebble</strong> (<strong>Feeds</strong> tab &rarr; <strong>Scan QR</strong>) to sync your isolated feed to your watch.</p>

          <div id="qr-available">
            <button id="qr-toggle-btn" class="qr-box qr-hidden-box" type="button" aria-expanded="false" aria-controls="qr-revealed-meta" aria-label="Click to view pairing QR code">
              <span id="qr-placeholder" class="qr-placeholder">
                <svg class="qr-placeholder-icon" viewBox="0 0 24 24" width="36" height="36" aria-hidden="true">
                  <path fill="currentColor" d="M3 11h8V3H3v8zm2-6h4v4H5V5zM3 21h8v-8H3v8zm2-6h4v4H5v-4zm8-12v8h8V3h-8zm6 6h-4V5h4v4zm-6 4h2v2h-2v-2zm2 2h2v2h-2v-2zm-2 2h2v2h-2v-2zm2 2h2v2h-2v-2zm2-2h2v2h-2v-2zm2-2h2v2h-2v-2zm0-4h2v2h-2v-2zm-2 2h2v2h-2v-2z"/>
                </svg>
                <span class="qr-placeholder-title">Click to view QR code</span>
                <span class="qr-placeholder-sub">Contains your read token &middot; auto-hides in 30s</span>
              </span>
              <img id="qr-img" class="hidden" alt="Pairing QR code for Pin Manager for Pebble">
            </button>
            <div id="qr-revealed-meta" class="hidden">
              <p id="qr-countdown" class="qr-countdown" role="status" aria-live="polite"></p>
              <div class="btn-row centered-row">
                <a id="pairing-link" class="btn btn-primary" href="#">Open in Pin Manager for Pebble</a>
              </div>
            </div>
          </div>

          <div id="qr-unavailable" class="notice hidden">
            <strong>Retrievable tokens not yet initialized</strong>
            This account was created before token sealing was enabled. Generate fresh tokens below to view your QR code and copy API tokens.
          </div>

          <form id="profile-form" class="inline-form">
            <div class="field">
              <label for="profile-name">Feed / Device Name</label>
              <div class="input-with-btn">
                <input id="profile-name" type="text" maxlength="80" placeholder="My Pebble">
                <button class="btn btn-secondary" type="submit">Save</button>
              </div>
            </div>
          </form>

          <div class="kv-item">
            <div class="kv-label">Feed URL</div>
            <div class="copy-row">
              <code id="feed-url-display" class="mono-wrap"></code>
              <button id="copy-feed-url-btn" class="btn btn-secondary btn-sm" type="button">Copy</button>
            </div>
          </div>

          <div id="http-warning" class="notice hidden">
            <strong>Unencrypted HTTP URL</strong>
            Use plain HTTP only on a trusted LAN. Terminate HTTPS in a reverse proxy for internet access.
          </div>
          <div id="localhost-warning" class="notice hidden">
            <strong>Localhost address detected</strong>
            Your phone cannot reach <code>localhost</code>. Access via your LAN/public hostname or set <code>PIN_SERVER_PUBLIC_URL</code>.
          </div>
        </section>

        <section id="tokens-card" class="card hidden" aria-labelledby="tokens-heading">
          <h2 id="tokens-heading">API Tokens &amp; Scripts</h2>
          <p class="muted">Use your <strong>Write API Token</strong> in scripts, cron jobs, webhooks, or Home Assistant to push pins automatically.</p>

          <div class="kv-item">
            <div class="kv-label">User ID</div>
            <div class="copy-row">
              <code id="user-id-display" class="mono-wrap"></code>
              <button id="copy-user-id-btn" class="btn btn-secondary btn-sm" type="button">Copy</button>
            </div>
          </div>

          <div class="kv-item">
            <div class="kv-label">Pins API Endpoint</div>
            <div class="copy-row">
              <code id="pins-api-url-display" class="mono-wrap"></code>
              <button id="copy-api-url-btn" class="btn btn-secondary btn-sm" type="button">Copy</button>
            </div>
          </div>

          <div class="btn-row">
            <button id="reveal-tokens-btn" class="btn btn-primary btn-sm" type="button">Reveal Tokens</button>
            <button id="hide-tokens-btn" class="btn btn-secondary btn-sm hidden" type="button">Hide Tokens</button>
          </div>

          <div id="tokens-revealed" class="tokens-panel hidden">
            <div class="kv-item">
              <div class="kv-label">Write API Token (scripts / CRUD)</div>
              <div class="copy-row">
                <code id="write-token-display" class="mono-wrap"></code>
                <button id="copy-write-token-btn" class="btn btn-secondary btn-sm" type="button">Copy</button>
              </div>
            </div>

            <div class="kv-item">
              <div class="kv-label">Read API Token (phone feed / read-only)</div>
              <div class="copy-row">
                <code id="read-token-display" class="mono-wrap"></code>
                <button id="copy-read-token-btn" class="btn btn-secondary btn-sm" type="button">Copy</button>
              </div>
            </div>
          </div>

          <div class="kv-item">
            <div class="label-row">
              <span class="kv-label">Quick <code>curl</code> Example</span>
              <button id="copy-curl-btn" class="btn btn-secondary btn-sm" type="button">Copy curl</button>
            </div>
            <pre id="curl-example"></pre>
          </div>

          <details class="advanced-section">
            <summary>Rotate API Tokens</summary>
            <p class="hint">Rotating the <strong>Write Token</strong> revokes old script tokens without affecting your paired phone. Rotating the <strong>Read Token</strong> updates your QR code and requires re-pairing your phone.</p>
            <div class="btn-row">
              <button id="rotate-write-btn" class="btn btn-secondary btn-sm" type="button">Rotate Write Token</button>
              <button id="rotate-read-btn" class="btn btn-secondary btn-sm" type="button">Rotate Read Token</button>
              <button id="rotate-both-btn" class="btn btn-danger btn-sm" type="button">Rotate Both</button>
            </div>
          </details>
        </section>

        <section id="legacy-card" class="card hidden" aria-labelledby="legacy-heading">
          <h2 id="legacy-heading">Legacy Single-User Feed</h2>
          <p class="muted">You are signed in with the global admin write token managing the legacy feed.</p>
          <div class="kv-item">
            <div class="kv-label">Feed URL</div>
            <div class="copy-row">
              <code id="legacy-feed-url" class="mono-wrap"></code>
              <button id="copy-legacy-feed-btn" class="btn btn-secondary btn-sm" type="button">Copy</button>
            </div>
          </div>
          <div class="kv-item">
            <div class="kv-label">Pins API Endpoint</div>
            <div class="copy-row">
              <code id="legacy-api-url" class="mono-wrap"></code>
              <button id="copy-legacy-api-btn" class="btn btn-secondary btn-sm" type="button">Copy</button>
            </div>
          </div>
        </section>
      </aside>
    </div>
  </div>
  <script src="/ui/app.js"></script>
</body>
</html>
`))

const webUICSS = `
:root {
  color-scheme: light dark;
  --bg: #f4f6f8;
  --card: #ffffff;
  --fg: #182026;
  --muted: #5c7080;
  --border: #dce3ea;
  --accent: #0f62fe;
  --accent-hover: #0353e9;
  --accent-soft: rgba(15, 98, 254, 0.12);
  --accent-fg: #ffffff;
  --danger: #d93025;
  --danger-fg: #ffffff;
  --warn-bg: #fff3cd;
  --warn-border: #f0b429;
  --warn-fg: #664d03;
  --ok-bg: #e6f4ea;
  --ok-border: #81c995;
  --ok-fg: #137333;
  --err-bg: #fce8e6;
  --err-border: #f28b82;
  --err-fg: #a50e0e;
  --code-bg: #f1f3f5;
}
@media (prefers-color-scheme: dark) {
  :root {
    --bg: #0f1419;
    --card: #1a222b;
    --fg: #eef2f6;
    --muted: #9aa8b6;
    --border: #2c3846;
    --accent: #4589ff;
    --accent-hover: #6ea6ff;
    --accent-soft: rgba(69, 137, 255, 0.18);
    --accent-fg: #0b1220;
    --danger: #f28b82;
    --danger-fg: #2b0b0a;
    --warn-bg: #3b2d0a;
    --warn-border: #d99b1e;
    --warn-fg: #ffe08a;
    --ok-bg: #123321;
    --ok-border: #34a853;
    --ok-fg: #81c995;
    --err-bg: #3c1618;
    --err-border: #f28b82;
    --err-fg: #f6aea9;
    --code-bg: #111820;
  }
}
* { box-sizing: border-box; }
body {
  margin: 0;
  padding: 20px 16px 48px;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
  background: var(--bg);
  color: var(--fg);
  line-height: 1.5;
}
.hidden { display: none !important; }
.app-shell {
  max-width: 1080px;
  margin: 0 auto;
}
.topbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 18px;
}
.brand h1 {
  margin: 0;
  font-size: 1.4rem;
  line-height: 1.25;
}
.subtitle {
  margin: 2px 0 0;
  color: var(--muted);
  font-size: 0.92rem;
}
.topbar-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.dashboard-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.45fr) minmax(300px, 1fr);
  gap: 18px;
  align-items: start;
}
@media (max-width: 860px) {
  .dashboard-grid {
    grid-template-columns: 1fr;
  }
}
.primary-col, .side-col {
  display: flex;
  flex-direction: column;
  gap: 18px;
  min-width: 0;
}
.card {
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: 16px;
  padding: 20px;
  box-shadow: 0 4px 18px rgba(0, 0, 0, 0.06);
}
.auth-card {
  max-width: 480px;
  margin: 24px auto 0;
}
.centered {
  text-align: center;
}
.card-header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 14px;
}
.header-title-group, .header-controls {
  display: flex;
  align-items: center;
  gap: 10px;
}
h2 {
  margin: 0 0 10px;
  font-size: 1.1rem;
}
.card-header h2 { margin-bottom: 0; }
.muted {
  color: var(--muted);
  font-size: 0.9rem;
  margin: 0 0 14px;
}
.field {
  margin-bottom: 14px;
}
.field-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}
@media (max-width: 560px) {
  .field-row { grid-template-columns: 1fr; }
}
.label-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
}
label {
  display: block;
  font-weight: 600;
  font-size: 0.88rem;
  margin-bottom: 5px;
}
.char-count {
  font-size: 0.78rem;
  color: var(--muted);
}
input[type="text"],
input[type="password"],
input[type="number"],
input[type="datetime-local"],
select,
textarea {
  width: 100%;
  padding: 9px 11px;
  border-radius: 10px;
  border: 1px solid var(--border);
  background: var(--bg);
  color: var(--fg);
  font-family: inherit;
  font-size: 0.93rem;
}
textarea { resize: vertical; }
input:disabled { opacity: 0.65; }
.input-with-btn {
  display: flex;
  gap: 8px;
}
.input-with-btn input { flex: 1; }
.hint {
  margin: 6px 0 0;
  color: var(--muted);
  font-size: 0.82rem;
}
.btn-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}
.centered-row { justify-content: center; }
.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 9px 15px;
  border-radius: 10px;
  border: 1px solid transparent;
  font-weight: 600;
  font-size: 0.9rem;
  cursor: pointer;
  text-decoration: none;
  font-family: inherit;
}
.btn-sm {
  padding: 6px 10px;
  font-size: 0.82rem;
  border-radius: 8px;
}
.btn-primary {
  background: var(--accent);
  color: var(--accent-fg);
}
.btn-primary:hover { background: var(--accent-hover); }
.btn-secondary {
  background: transparent;
  color: var(--fg);
  border-color: var(--border);
}
.btn-secondary:hover { background: var(--code-bg); }
.btn-danger {
  background: var(--danger);
  color: var(--danger-fg);
}
.btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.chip-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 6px;
}
.chip {
  padding: 3px 9px;
  border-radius: 999px;
  border: 1px solid var(--border);
  background: transparent;
  color: var(--fg);
  font-size: 0.78rem;
  cursor: pointer;
}
.chip:hover { background: var(--code-bg); }
.icon-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}
.icon-option {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  min-height: 58px;
  padding: 8px 4px;
  border-radius: 10px;
  border: 1px solid var(--border);
  background: var(--bg);
  color: var(--muted);
  cursor: pointer;
  font-family: inherit;
  transition: background-color 0.12s ease, border-color 0.12s ease, color 0.12s ease;
}
.icon-option:hover {
  background: var(--code-bg);
  color: var(--fg);
}
.icon-option[aria-checked="true"] {
  background: var(--accent-soft);
  border-color: var(--accent);
  color: var(--accent);
  box-shadow: inset 0 0 0 1px var(--accent);
}
.icon-option:focus-visible,
.qr-box:focus-visible,
.swatch:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 2px;
}
.icon-svg {
  display: block;
  flex-shrink: 0;
}
.icon-option-label {
  font-size: 0.74rem;
  font-weight: 600;
  line-height: 1.15;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.swatch-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}
.swatch {
  width: 28px;
  height: 28px;
  border-radius: 999px;
  border: 2px solid var(--border);
  cursor: pointer;
  padding: 0;
  position: relative;
}
.swatch-none {
  width: auto;
  padding: 0 10px;
  font-size: 0.78rem;
  background: transparent;
  color: var(--fg);
}
.swatch[aria-checked="true"] {
  outline: 2px solid var(--accent);
  outline-offset: 2px;
}
.advanced-section {
  margin: 12px 0;
  border-top: 1px solid var(--border);
  padding-top: 10px;
}
.advanced-section summary {
  cursor: pointer;
  font-size: 0.88rem;
  font-weight: 600;
  color: var(--muted);
  margin-bottom: 10px;
}
.badge {
  display: inline-block;
  padding: 3px 9px;
  border-radius: 999px;
  font-size: 0.78rem;
  font-weight: 600;
  background: var(--code-bg);
  color: var(--fg);
}
.badge.icon-badge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.badge-ok { background: var(--ok-bg); color: var(--ok-fg); }
.alert {
  padding: 11px 14px;
  border-radius: 10px;
  font-size: 0.88rem;
  margin-bottom: 14px;
  border: 1px solid var(--border);
}
.alert-ok {
  background: var(--ok-bg);
  border-color: var(--ok-border);
  color: var(--ok-fg);
}
.alert-error {
  background: var(--err-bg);
  border-color: var(--err-border);
  color: var(--err-fg);
}
.notice {
  padding: 11px 13px;
  border-radius: 10px;
  border: 1px solid var(--warn-border);
  background: var(--warn-bg);
  color: var(--warn-fg);
  font-size: 0.84rem;
  margin-top: 12px;
}
.notice strong { display: block; margin-bottom: 2px; }
.qr-box {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  max-width: 260px;
  min-height: 224px;
  padding: 14px;
  margin: 0 auto 10px;
  background: #ffffff;
  border-radius: 12px;
  border: 1px solid var(--border);
  cursor: pointer;
  font-family: inherit;
  color: var(--fg);
  text-align: center;
  transition: border-color 0.15s ease, background-color 0.15s ease;
}
.qr-box:hover {
  border-color: var(--accent);
}
.qr-box:disabled {
  opacity: 0.7;
  cursor: wait;
}
.qr-hidden-box {
  background: var(--bg);
  border-style: dashed;
}
.qr-hidden-box:hover {
  background: var(--code-bg);
}
.qr-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 8px;
}
.qr-placeholder-icon {
  color: var(--accent);
  opacity: 0.9;
}
.qr-placeholder-title {
  font-weight: 700;
  font-size: 0.94rem;
  color: var(--fg);
}
.qr-placeholder-sub {
  font-size: 0.78rem;
  color: var(--muted);
  line-height: 1.35;
}
.qr-box img {
  display: block;
  width: 100%;
  max-width: 224px;
  height: auto;
}
.qr-countdown {
  text-align: center;
  font-size: 0.8rem;
  color: var(--muted);
  margin: 0 0 8px;
}
#qr-revealed-meta {
  margin-bottom: 14px;
}
.kv-item {
  margin-top: 12px;
}
.kv-label {
  font-size: 0.8rem;
  font-weight: 600;
  color: var(--muted);
  margin-bottom: 4px;
}
.copy-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
code {
  background: var(--code-bg);
  padding: 2px 6px;
  border-radius: 5px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 0.83em;
}
.mono-wrap {
  flex: 1;
  word-break: break-all;
  padding: 6px 8px;
  display: block;
}
pre {
  background: var(--code-bg);
  padding: 11px;
  border-radius: 10px;
  overflow-x: auto;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 0.78rem;
  line-height: 1.45;
  margin: 6px 0 0;
}
.tokens-panel {
  margin-top: 10px;
  padding: 10px 12px;
  border-radius: 10px;
  border: 1px solid var(--border);
  background: var(--bg);
}
.checkbox-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 0.84rem;
  font-weight: 500;
  color: var(--muted);
  margin: 0;
  cursor: pointer;
}
.checkbox-label input { width: auto; }
.pins-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.pin-item {
  display: flex;
  border: 1px solid var(--border);
  border-radius: 12px;
  overflow: hidden;
  background: var(--bg);
}
.pin-accent {
  width: 6px;
  flex-shrink: 0;
  background: var(--accent);
}
.pin-body {
  flex: 1;
  padding: 12px 14px;
  min-width: 0;
}
.pin-top {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  align-items: flex-start;
  gap: 8px;
}
.pin-title {
  font-weight: 700;
  font-size: 0.98rem;
  margin: 0;
  word-break: break-word;
}
.pin-meta {
  font-size: 0.84rem;
  color: var(--muted);
  margin-top: 3px;
}
.pin-desc {
  margin: 7px 0 0;
  font-size: 0.88rem;
  white-space: pre-wrap;
  word-break: break-word;
}
.pin-footer {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 8px;
}
.pin-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}
.pin-actions {
  display: flex;
  gap: 6px;
}
.empty-state {
  text-align: center;
  padding: 24px 12px;
  color: var(--muted);
  font-size: 0.92rem;
}
`

const webUIJS = `(function () {
  "use strict";

  const ACCENT_COLORS = [
    { label: "None", hex: "" },
    { label: "Orange", hex: "#FF5500" },
    { label: "Red", hex: "#FF0000" },
    { label: "Sunset", hex: "#FF5555" },
    { label: "Yellow", hex: "#FFAA00" },
    { label: "Green", hex: "#00AA00" },
    { label: "Teal", hex: "#00AAAA" },
    { label: "Sky Blue", hex: "#00AAFF" },
    { label: "Cobalt", hex: "#0055AA" },
    { label: "Indigo", hex: "#5555FF" },
    { label: "Purple", hex: "#AA55FF" },
    { label: "Rose", hex: "#FF55AA" },
    { label: "Dark Gray", hex: "#555555" }
  ];

  const DEFAULT_ICON_URI = "system://images/TIMELINE_CALENDAR";
  const QR_VISIBLE_SECONDS = 30;
  const SVG_NS = "http://www.w3.org/2000/svg";

  const PIN_ICONS = [
    {
      uri: "system://images/TIMELINE_CALENDAR",
      label: "Calendar",
      path: "M19 4h-1V2h-2v2H8V2H6v2H5c-1.11 0-1.99.9-1.99 2L3 20c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm0 16H5V10h14v10zM9 14H7v-2h2v2zm4 0h-2v-2h2v2zm4 0h-2v-2h2v2zm-8 4H7v-2h2v2zm4 0h-2v-2h2v2zm4 0h-2v-2h2v2z"
    },
    {
      uri: "system://images/NOTIFICATION_FLAG",
      label: "Flag",
      path: "M14.4 6L14 4H5v17h2v-7h5.6l.4 2h7V6h-5.6z"
    },
    {
      uri: "system://images/TIMELINE_SPORTS",
      label: "Sports",
      strokeCircle: { cx: "12", cy: "12", r: "9.2" },
      strokePaths: [
        "M2.8 12h18.4",
        "M12 2.8v18.4",
        "M5.5 5.5a9.2 9.2 0 0 1 0 13",
        "M18.5 5.5a9.2 9.2 0 0 0 0 13"
      ]
    },
    {
      uri: "system://images/GENERIC_CONFIRMATION",
      label: "Checkmark",
      path: "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z"
    },
    {
      uri: "system://images/TIMELINE_WEATHER",
      label: "Weather",
      path: "M6.76 4.84l-1.8-1.79-1.41 1.41 1.79 1.79 1.42-1.41zM4 10.5H1v2h3v-2zm9-9.95h-2V3.5h2V.55zm7.45 3.91l-1.41-1.41-1.79 1.79 1.41 1.41 1.79-1.79zm-3.21 13.7l1.79 1.8 1.41-1.41-1.8-1.79-1.4 1.4zM20 10.5v2h3v-2h-3zm-8-5c-3.31 0-6 2.69-6 6s2.69 6 6 6 6-2.69 6-6-2.69-6-6-6zm-1 16.95h2V19.5h-2v2.95zm-7.45-3.91l1.41 1.41 1.79-1.8-1.41-1.41-1.79 1.8z"
    },
    {
      uri: "system://images/AUDIO_CASSETTE",
      label: "Audio",
      path: "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 14.5c-2.49 0-4.5-2.01-4.5-4.5S9.51 7.5 12 7.5s4.5 2.01 4.5 4.5-2.01 4.5-4.5 4.5zm0-5.5c-.55 0-1 .45-1 1s.45 1 1 1 1-.45 1-1-.45-1-1-1z"
    },
    {
      uri: "system://images/ALARM_CLOCK",
      label: "Alarm",
      path: "M22 5.72l-4.6-3.86-1.29 1.53 4.6 3.86L22 5.72zM7.88 3.39L6.6 1.86 2 5.71l1.29 1.53 4.59-3.85zM12.5 8H11v6l4.75 2.85.75-1.23-4-2.37V8zM12 4c-4.97 0-9 4.03-9 9s4.02 9 9 9c4.97 0 9-4.03 9-9s-4.03-9-9-9zm0 16c-3.87 0-7-3.13-7-7s3.13-7 7-7 7 3.13 7 7-3.13 7-7 7z"
    },
    {
      uri: "system://images/DINNER_RESERVATION",
      label: "Dining",
      path: "M11 9H9V2H7v7H5V2H3v7c0 2.12 1.66 3.84 3.75 3.97V22h2.5v-9.03C11.34 12.84 13 11.12 13 9V2h-2v7zm5-3v8h2.5v8H21V2c-2.76 0-5 2.24-5 4z"
    },
    {
      uri: "system://images/SCHEDULED_FLIGHT",
      label: "Flight",
      path: "M21 16v-2l-8-5V3.5c0-.83-.67-1.5-1.5-1.5S10 2.67 10 3.5V9l-8 5v2l8-2.5V19l-2 1.5V22l3.5-1 3.5 1v-1.5L13 19v-5.5l8 2.5z"
    },
    {
      uri: "system://images/CAR_RENTAL",
      label: "Travel",
      path: "M18.92 6.01C18.72 5.42 18.16 5 17.5 5h-11c-.66 0-1.21.42-1.42 1.01L3 12v8c0 .55.45 1 1 1h1c.55 0 1-.45 1-1v-1h12v1c0 .55.45 1 1 1h1c.55 0 1-.45 1-1v-8l-2.08-5.99zM6.5 16c-.83 0-1.5-.67-1.5-1.5S5.67 13 6.5 13s1.5.67 1.5 1.5S7.33 16 6.5 16zm11 0c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5zM5 11l1.5-4.5h11L19 11H5z"
    },
    {
      uri: "system://images/NOTIFICATION_REMINDER",
      label: "Reminder",
      path: "M12 22c1.1 0 2-.9 2-2h-4c0 1.1.89 2 2 2zm6-6v-5c0-3.07-1.64-5.64-4.5-6.32V4c0-.83-.67-1.5-1.5-1.5s-1.5.67-1.5 1.5v.68C7.63 5.36 6 7.92 6 11v5l-2 2v1h16v-1l-2-2z"
    },
    {
      uri: "system://images/LOCATION",
      label: "Location",
      path: "M12 2C8.13 2 5 5.13 5 9c0 5.25 7 13 7 13s7-7.75 7-13c0-3.87-3.13-7-7-7zm0 9.5c-1.38 0-2.5-1.12-2.5-2.5s1.12-2.5 2.5-2.5 2.5 1.12 2.5 2.5-1.12 2.5-2.5 2.5z"
    },
    {
      uri: "system://images/BIRTHDAY_EVENT",
      label: "Birthday",
      path: "M12 6c1.11 0 2-.9 2-2 0-.38-.1-.73-.29-1.03L12 0l-1.71 2.97c-.19.3-.29.65-.29 1.03 0 1.1.9 2 2 2zm4.6 9.99l-1.07-1.07-1.08 1.07c-1.3 1.3-3.58 1.31-4.89 0l-1.07-1.07-1.09 1.07C6.75 16.64 5.88 17 4.96 17c-.73 0-1.4-.23-1.96-.61V21c0 .55.45 1 1 1h16c.55 0 1-.45 1-1v-4.61c-.56.38-1.23.61-1.96.61-.92 0-1.79-.36-2.44-1.01zM18 9h-5V7h-2v2H6c-1.66 0-3 1.34-3 3v1.54c0 1.08.88 1.96 1.96 1.96.52 0 1.02-.2 1.38-.57l2.14-2.13 2.13 2.13c.74.74 2.03.74 2.77 0l2.14-2.13 2.13 2.13c.37.37.86.57 1.38.57 1.08 0 1.96-.88 1.96-1.96V12C21 10.34 19.66 9 18 9z"
    },
    {
      uri: "system://images/MOVIE_EVENT",
      label: "Movie",
      path: "M18 4l2 4h-3l-2-4h-2l2 4h-3l-2-4H8l2 4H7L5 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V4h-4z"
    },
    {
      uri: "system://images/MUSIC_EVENT",
      label: "Music",
      path: "M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z"
    },
    {
      uri: "system://images/GENERIC_EMAIL",
      label: "Mail",
      path: "M20 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm0 4l-8 5-8-5V6l8 5 8-5v2z"
    }
  ];

  const FALLBACK_ICON_PATH = "M17.63 5.84C17.27 5.33 16.67 5 16 5L5 5.01C3.9 5.01 3 5.9 3 7v10c0 1.1.9 1.99 2 1.99L16 19c.67 0 1.27-.33 1.63-.84L22 12l-4.37-6.16z";

  const ICON_BY_URI = {};
  PIN_ICONS.forEach(function (icon) {
    ICON_BY_URI[icon.uri] = icon;
  });

  const csrfMeta = document.querySelector('meta[name="csrf-token"]');
  const csrfToken = csrfMeta ? csrfMeta.getAttribute("content") || "" : "";

  const state = {
    session: null,
    pins: [],
    editingPinId: null,
    revealedTokens: null,
    qrVisible: false,
    qrLoading: false,
    qrCountdownTimer: null,
    qrRemainingSeconds: 0
  };

  const els = {
    loadingView: document.getElementById("loading-view"),
    loginView: document.getElementById("login-view"),
    dashboardView: document.getElementById("dashboard-view"),
    globalAlert: document.getElementById("global-alert"),
    serverSubtitle: document.getElementById("server-subtitle"),
    authBadge: document.getElementById("auth-badge"),
    logoutBtn: document.getElementById("logout-btn"),
    loginForm: document.getElementById("login-form"),
    loginToken: document.getElementById("login-token"),
    loginError: document.getElementById("login-error"),
    loginSubmit: document.getElementById("login-submit"),
    loginRegisterLink: document.getElementById("login-register-link"),
    loginProxyHint: document.getElementById("login-proxy-hint"),

    composerHeading: document.getElementById("composer-heading"),
    cancelEditBtn: document.getElementById("cancel-edit-btn"),
    pinForm: document.getElementById("pin-form"),
    pinTitle: document.getElementById("pin-title"),
    pinTitleCount: document.getElementById("pin-title-count"),
    pinStart: document.getElementById("pin-start"),
    pinDuration: document.getElementById("pin-duration"),
    durationPresets: document.getElementById("duration-presets"),
    pinDescription: document.getElementById("pin-description"),
    pinDescriptionCount: document.getElementById("pin-description-count"),
    iconPicker: document.getElementById("icon-picker"),
    pinIconSelectedName: document.getElementById("pin-icon-selected-name"),
    pinIcon: document.getElementById("pin-icon"),
    pinLayout: document.getElementById("pin-layout"),
    colorSwatches: document.getElementById("color-swatches"),
    pinColor: document.getElementById("pin-color"),
    pinId: document.getElementById("pin-id"),
    pinFormError: document.getElementById("pin-form-error"),
    pinSubmitBtn: document.getElementById("pin-submit-btn"),
    pinResetBtn: document.getElementById("pin-reset-btn"),

    pinsCount: document.getElementById("pins-count"),
    includeExpired: document.getElementById("include-expired"),
    refreshPinsBtn: document.getElementById("refresh-pins-btn"),
    pinsList: document.getElementById("pins-list"),

    pairCard: document.getElementById("pair"),
    qrAvailable: document.getElementById("qr-available"),
    qrUnavailable: document.getElementById("qr-unavailable"),
    qrToggleBtn: document.getElementById("qr-toggle-btn"),
    qrPlaceholder: document.getElementById("qr-placeholder"),
    qrImg: document.getElementById("qr-img"),
    qrRevealedMeta: document.getElementById("qr-revealed-meta"),
    qrCountdown: document.getElementById("qr-countdown"),
    pairingLink: document.getElementById("pairing-link"),
    profileForm: document.getElementById("profile-form"),
    profileName: document.getElementById("profile-name"),
    feedUrlDisplay: document.getElementById("feed-url-display"),
    copyFeedUrlBtn: document.getElementById("copy-feed-url-btn"),
    httpWarning: document.getElementById("http-warning"),
    localhostWarning: document.getElementById("localhost-warning"),

    tokensCard: document.getElementById("tokens-card"),
    userIdDisplay: document.getElementById("user-id-display"),
    copyUserIdBtn: document.getElementById("copy-user-id-btn"),
    pinsApiUrlDisplay: document.getElementById("pins-api-url-display"),
    copyApiUrlBtn: document.getElementById("copy-api-url-btn"),
    revealTokensBtn: document.getElementById("reveal-tokens-btn"),
    hideTokensBtn: document.getElementById("hide-tokens-btn"),
    tokensRevealed: document.getElementById("tokens-revealed"),
    writeTokenDisplay: document.getElementById("write-token-display"),
    readTokenDisplay: document.getElementById("read-token-display"),
    copyWriteTokenBtn: document.getElementById("copy-write-token-btn"),
    copyReadTokenBtn: document.getElementById("copy-read-token-btn"),
    curlExample: document.getElementById("curl-example"),
    copyCurlBtn: document.getElementById("copy-curl-btn"),
    rotateWriteBtn: document.getElementById("rotate-write-btn"),
    rotateReadBtn: document.getElementById("rotate-read-btn"),
    rotateBothBtn: document.getElementById("rotate-both-btn"),

    legacyCard: document.getElementById("legacy-card"),
    legacyFeedUrl: document.getElementById("legacy-feed-url"),
    legacyApiUrl: document.getElementById("legacy-api-url"),
    copyLegacyFeedBtn: document.getElementById("copy-legacy-feed-btn"),
    copyLegacyApiBtn: document.getElementById("copy-legacy-api-btn")
  };

  function showAlert(el, message, isError) {
    if (!message) {
      el.classList.add("hidden");
      el.textContent = "";
      return;
    }
    el.textContent = message;
    el.classList.remove("hidden", "alert-ok", "alert-error");
    el.classList.add(isError ? "alert-error" : "alert-ok");
  }

  let toastTimer = null;
  function flashGlobal(message, isError) {
    showAlert(els.globalAlert, message, isError);
    if (toastTimer) {
      clearTimeout(toastTimer);
    }
    if (message && !isError) {
      toastTimer = setTimeout(function () {
        showAlert(els.globalAlert, "", false);
      }, 4500);
    }
  }

  async function apiFetch(url, options) {
    const opts = options ? Object.assign({}, options) : {};
    const headers = Object.assign({ "Accept": "application/json" }, opts.headers || {});
    const method = (opts.method || "GET").toUpperCase();
    if (method !== "GET" && method !== "HEAD") {
      headers["X-CSRF-Token"] = csrfToken;
      if (opts.body !== undefined && !headers["Content-Type"]) {
        headers["Content-Type"] = "application/json";
      }
    }
    opts.headers = headers;
    opts.credentials = "same-origin";

    const resp = await fetch(url, opts);
    if (resp.status === 204) {
      return { ok: true, status: 204, data: null };
    }
    let data = null;
    try {
      data = await resp.json();
    } catch (_err) {
      data = null;
    }
    return { ok: resp.ok, status: resp.status, data: data };
  }

  function formatApiError(res, fallback) {
    if (res && res.data) {
      if (Array.isArray(res.data.details) && res.data.details.length > 0) {
        return res.data.details.map(function (d) {
          return d.field ? d.field + ": " + d.message : d.message;
        }).join(" ");
      }
      if (res.data.message) {
        return res.data.message;
      }
    }
    return fallback;
  }

  function toLocalInputValue(date) {
    const pad = function (n) { return String(n).padStart(2, "0"); };
    return (
      date.getFullYear() + "-" +
      pad(date.getMonth() + 1) + "-" +
      pad(date.getDate()) + "T" +
      pad(date.getHours()) + ":" +
      pad(date.getMinutes())
    );
  }

  function defaultStartDate() {
    const d = new Date(Date.now() + 60 * 60 * 1000);
    d.setMinutes(0, 0, 0);
    return d;
  }

  function localInputToRFC3339(raw) {
    if (!raw) return "";
    const parsed = new Date(raw);
    if (Number.isNaN(parsed.getTime())) return "";
    return parsed.toISOString().replace(/\.\d{3}Z$/, "Z");
  }

  function formatDisplayDate(iso) {
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return iso;
    return d.toLocaleString(undefined, {
      weekday: "short",
      month: "short",
      day: "numeric",
      hour: "2-digit",
      minute: "2-digit"
    });
  }

  function updateCounters() {
    els.pinTitleCount.textContent = els.pinTitle.value.length + " / 128";
    els.pinDescriptionCount.textContent = els.pinDescription.value.length + " / 512";
  }

  function iconLabelFor(uri) {
    if (uri && ICON_BY_URI[uri]) {
      return ICON_BY_URI[uri].label;
    }
    return (uri || "").replace("system://images/", "") || "Calendar";
  }

  function createIconSvg(uri, size) {
    const icon = uri && ICON_BY_URI[uri] ? ICON_BY_URI[uri] : null;
    const svg = document.createElementNS(SVG_NS, "svg");
    svg.setAttribute("viewBox", "0 0 24 24");
    svg.setAttribute("width", String(size));
    svg.setAttribute("height", String(size));
    svg.setAttribute("aria-hidden", "true");
    svg.setAttribute("class", "icon-svg");

    if (icon && icon.strokePaths) {
      if (icon.strokeCircle) {
        const circle = document.createElementNS(SVG_NS, "circle");
        circle.setAttribute("cx", icon.strokeCircle.cx);
        circle.setAttribute("cy", icon.strokeCircle.cy);
        circle.setAttribute("r", icon.strokeCircle.r);
        circle.setAttribute("fill", "none");
        circle.setAttribute("stroke", "currentColor");
        circle.setAttribute("stroke-width", "1.9");
        svg.appendChild(circle);
      }
      icon.strokePaths.forEach(function (d) {
        const p = document.createElementNS(SVG_NS, "path");
        p.setAttribute("d", d);
        p.setAttribute("fill", "none");
        p.setAttribute("stroke", "currentColor");
        p.setAttribute("stroke-width", "1.7");
        p.setAttribute("stroke-linecap", "round");
        svg.appendChild(p);
      });
      return svg;
    }

    const path = document.createElementNS(SVG_NS, "path");
    path.setAttribute("fill", "currentColor");
    path.setAttribute("d", icon && icon.path ? icon.path : FALLBACK_ICON_PATH);
    svg.appendChild(path);
    return svg;
  }

  function buildIconButton(uri, label, isCustom) {
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "icon-option";
    btn.dataset.iconUri = uri;
    if (isCustom) {
      btn.dataset.customIcon = "true";
    }
    btn.title = label + " (" + uri.replace("system://images/", "") + ")";
    btn.setAttribute("role", "radio");
    btn.setAttribute("aria-label", label);
    btn.setAttribute("aria-checked", "false");

    btn.appendChild(createIconSvg(uri, 22));

    const text = document.createElement("span");
    text.className = "icon-option-label";
    text.textContent = label;
    btn.appendChild(text);

    btn.addEventListener("click", function () {
      setSelectedIcon(uri);
    });
    return btn;
  }

  function clearCustomIconOptions() {
    const custom = els.iconPicker.querySelectorAll('button.icon-option[data-custom-icon="true"]');
    custom.forEach(function (node) {
      node.remove();
    });
  }

  function setSelectedIcon(uri) {
    const normalized = uri || DEFAULT_ICON_URI;
    if (!ICON_BY_URI[normalized]) {
      clearCustomIconOptions();
      els.iconPicker.appendChild(buildIconButton(normalized, iconLabelFor(normalized), true));
    }
    els.pinIcon.value = normalized;
    els.pinIconSelectedName.textContent = iconLabelFor(normalized);
    const buttons = els.iconPicker.querySelectorAll("button.icon-option");
    buttons.forEach(function (btn) {
      const selected = (btn.dataset.iconUri || "") === normalized;
      btn.setAttribute("aria-checked", selected ? "true" : "false");
    });
  }

  function initIconPicker() {
    els.iconPicker.textContent = "";
    PIN_ICONS.forEach(function (item) {
      els.iconPicker.appendChild(buildIconButton(item.uri, item.label, false));
    });
    setSelectedIcon(DEFAULT_ICON_URI);
  }

  function setSelectedColor(hex) {
    els.pinColor.value = hex || "";
    const buttons = els.colorSwatches.querySelectorAll("button.swatch");
    buttons.forEach(function (btn) {
      const selected = (btn.dataset.color || "") === (hex || "");
      btn.setAttribute("aria-checked", selected ? "true" : "false");
    });
  }

  function initColorSwatches() {
    els.colorSwatches.textContent = "";
    ACCENT_COLORS.forEach(function (item) {
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = item.hex ? "swatch" : "swatch swatch-none";
      btn.dataset.color = item.hex;
      btn.title = item.label;
      btn.setAttribute("role", "radio");
      btn.setAttribute("aria-label", item.label);
      if (item.hex) {
        btn.style.backgroundColor = item.hex;
      } else {
        btn.textContent = "None";
      }
      btn.addEventListener("click", function () {
        setSelectedColor(item.hex);
      });
      els.colorSwatches.appendChild(btn);
    });
    setSelectedColor("");
  }

  function resetComposer() {
    state.editingPinId = null;
    els.composerHeading.textContent = "Add Pin";
    els.pinSubmitBtn.textContent = "Add Pin";
    els.cancelEditBtn.classList.add("hidden");
    els.pinTitle.value = "";
    els.pinStart.value = toLocalInputValue(defaultStartDate());
    els.pinDuration.value = "";
    els.pinDescription.value = "";
    clearCustomIconOptions();
    setSelectedIcon(DEFAULT_ICON_URI);
    els.pinLayout.value = "GENERIC_PIN";
    els.pinId.value = "";
    els.pinId.disabled = false;
    setSelectedColor("");
    showAlert(els.pinFormError, "", false);
    updateCounters();
  }

  function startEditingPin(pin) {
    state.editingPinId = pin.id;
    els.composerHeading.textContent = "Edit Pin";
    els.pinSubmitBtn.textContent = "Save Changes";
    els.cancelEditBtn.classList.remove("hidden");
    els.pinTitle.value = pin.title || "";
    const parsed = new Date(pin.startTime);
    els.pinStart.value = Number.isNaN(parsed.getTime()) ? toLocalInputValue(defaultStartDate()) : toLocalInputValue(parsed);
    els.pinDuration.value = pin.durationMinutes != null ? String(pin.durationMinutes) : "";
    els.pinDescription.value = pin.description || "";

    clearCustomIconOptions();
    setSelectedIcon(pin.iconUri || DEFAULT_ICON_URI);
    els.pinLayout.value = (pin.layoutType === "CALENDAR_PIN" || pin.layoutType === "GENERIC_PIN")
      ? pin.layoutType
      : "GENERIC_PIN";
    setSelectedColor(pin.backgroundColor || "");
    els.pinId.value = pin.id || "";
    els.pinId.disabled = true;
    showAlert(els.pinFormError, "", false);
    updateCounters();
    els.pinTitle.focus();
    window.scrollTo({ top: 0, behavior: "smooth" });
  }

  async function copyText(text, label) {
    if (!text) return;
    try {
      await navigator.clipboard.writeText(text);
      flashGlobal(label + " copied to clipboard.", false);
    } catch (_err) {
      flashGlobal("Clipboard access denied — select and copy manually.", true);
    }
  }

  function renderCurlExample() {
    if (!state.session || !state.session.pinsApiUrl) return;
    const tokenVal = state.revealedTokens && state.revealedTokens.writeToken
      ? state.revealedTokens.writeToken
      : "$WRITE_TOKEN";
    const sampleTime = defaultStartDate().toISOString().replace(/\.\d{3}Z$/, "Z");
    const cmd = [
      'curl -sf -X POST "' + state.session.pinsApiUrl + '" \\',
      '  -H "Authorization: Bearer ' + tokenVal + '" \\',
      '  -H "Content-Type: application/json" \\',
      "  -d '{\"title\":\"Hello Pebble\",\"startTime\":\"" + sampleTime + "\"}'"
    ].join("\n");
    els.curlExample.textContent = cmd;
  }

  function renderTokensPanel() {
    if (state.revealedTokens && state.revealedTokens.available) {
      els.writeTokenDisplay.textContent = state.revealedTokens.writeToken || "";
      els.readTokenDisplay.textContent = state.revealedTokens.readToken || "";
      els.tokensRevealed.classList.remove("hidden");
      els.revealTokensBtn.classList.add("hidden");
      els.hideTokensBtn.classList.remove("hidden");
    } else {
      els.writeTokenDisplay.textContent = "";
      els.readTokenDisplay.textContent = "";
      els.tokensRevealed.classList.add("hidden");
      els.revealTokensBtn.classList.remove("hidden");
      els.hideTokensBtn.classList.add("hidden");
    }
    renderCurlExample();
  }

  function hideQR() {
    if (state.qrCountdownTimer) {
      clearInterval(state.qrCountdownTimer);
      state.qrCountdownTimer = null;
    }
    state.qrVisible = false;
    state.qrRemainingSeconds = 0;
    els.qrImg.classList.add("hidden");
    els.qrImg.removeAttribute("src");
    els.qrPlaceholder.classList.remove("hidden");
    els.qrToggleBtn.classList.add("qr-hidden-box");
    els.qrToggleBtn.setAttribute("aria-expanded", "false");
    els.qrToggleBtn.setAttribute("aria-label", "Click to view pairing QR code");
    els.qrRevealedMeta.classList.add("hidden");
    els.qrCountdown.textContent = "";
    els.pairingLink.href = "#";
  }

  function updateQRCountdownLabel() {
    els.qrCountdown.textContent = "Hiding in " + state.qrRemainingSeconds + "s · Click QR to hide";
  }

  function showQR(data) {
    hideQR();
    els.qrImg.src = data.qrDataUri;
    els.qrImg.classList.remove("hidden");
    els.qrPlaceholder.classList.add("hidden");
    els.qrToggleBtn.classList.remove("qr-hidden-box");
    els.qrToggleBtn.setAttribute("aria-expanded", "true");
    els.qrToggleBtn.setAttribute("aria-label", "Hide pairing QR code");
    els.pairingLink.href = data.pairingUri || "#";
    els.qrRevealedMeta.classList.remove("hidden");

    state.qrVisible = true;
    state.qrRemainingSeconds = QR_VISIBLE_SECONDS;
    updateQRCountdownLabel();

    state.qrCountdownTimer = setInterval(function () {
      state.qrRemainingSeconds -= 1;
      if (state.qrRemainingSeconds <= 0) {
        hideQR();
        return;
      }
      updateQRCountdownLabel();
    }, 1000);
  }

  async function toggleQR() {
    if (state.qrVisible) {
      hideQR();
      return;
    }
    if (state.qrLoading) {
      return;
    }
    state.qrLoading = true;
    els.qrToggleBtn.disabled = true;
    try {
      const res = await apiFetch("/ui/api/pairing", { method: "POST" });
      if (!res.ok || !res.data) {
        flashGlobal(formatApiError(res, "Failed to load pairing QR code."), true);
        return;
      }
      if (!res.data.available || !res.data.qrDataUri) {
        flashGlobal("Pairing QR is not available for this account yet — use Rotate Tokens below.", true);
        return;
      }
      showQR(res.data);
    } finally {
      state.qrLoading = false;
      els.qrToggleBtn.disabled = false;
    }
  }

  function renderSession(sess) {
    state.session = sess;
    hideQR();
    els.loadingView.classList.add("hidden");

    if (sess.serverName) {
      els.serverSubtitle.textContent = sess.serverName;
    }

    if (!sess.authenticated) {
      els.dashboardView.classList.add("hidden");
      els.loginView.classList.remove("hidden");
      els.authBadge.classList.add("hidden");
      els.logoutBtn.classList.add("hidden");
      els.loginRegisterLink.classList.toggle("hidden", !sess.registrationEnabled);
      els.loginProxyHint.classList.toggle("hidden", !sess.authProxyConfigured);
      return;
    }

    els.loginView.classList.add("hidden");
    els.dashboardView.classList.remove("hidden");
    els.authBadge.classList.remove("hidden", "badge-ok");

    if (sess.authMode === "proxy") {
      els.authBadge.textContent = "SSO: " + (sess.proxySubject || (sess.user && sess.user.name) || "Authenticated");
      els.authBadge.classList.add("badge-ok");
      els.logoutBtn.classList.add("hidden");
    } else if (sess.authMode === "user_token") {
      els.authBadge.textContent = "User Session";
      els.authBadge.classList.add("badge-ok");
      els.logoutBtn.classList.remove("hidden");
    } else {
      els.authBadge.textContent = "Admin · Legacy Feed";
      els.logoutBtn.classList.remove("hidden");
    }

    if (sess.isLegacy) {
      els.pairCard.classList.add("hidden");
      els.tokensCard.classList.add("hidden");
      els.legacyCard.classList.remove("hidden");
      els.legacyFeedUrl.textContent = sess.feedUrl || "";
      els.legacyApiUrl.textContent = sess.pinsApiUrl || "";
      return;
    }

    els.legacyCard.classList.add("hidden");
    els.pairCard.classList.remove("hidden");
    els.tokensCard.classList.remove("hidden");

    if (sess.tokensAvailable) {
      els.qrAvailable.classList.remove("hidden");
      els.qrUnavailable.classList.add("hidden");
      els.revealTokensBtn.disabled = false;
      els.revealTokensBtn.textContent = "Reveal Tokens";
    } else {
      els.qrAvailable.classList.add("hidden");
      els.qrUnavailable.classList.remove("hidden");
      els.revealTokensBtn.disabled = true;
      els.revealTokensBtn.textContent = "Rotate Tokens to Enable Reveal";
    }

    els.profileName.value = (sess.user && sess.user.name) || "";
    els.feedUrlDisplay.textContent = sess.feedUrl || "";
    els.userIdDisplay.textContent = (sess.user && sess.user.id) || "";
    els.pinsApiUrlDisplay.textContent = sess.pinsApiUrl || "";

    els.httpWarning.classList.toggle("hidden", !sess.isHttp);
    els.localhostWarning.classList.toggle("hidden", !sess.isLocalhost);

    renderTokensPanel();
  }

  function renderPins(pins) {
    state.pins = Array.isArray(pins) ? pins : [];
    els.pinsCount.textContent = String(state.pins.length);
    els.pinsList.textContent = "";

    if (state.pins.length === 0) {
      const empty = document.createElement("div");
      empty.className = "empty-state";
      empty.textContent = "No pins yet. Add one above and sync your Pebble watch!";
      els.pinsList.appendChild(empty);
      return;
    }

    state.pins.forEach(function (pin) {
      const item = document.createElement("article");
      item.className = "pin-item";

      const accent = document.createElement("div");
      accent.className = "pin-accent";
      if (pin.backgroundColor) {
        accent.style.backgroundColor = pin.backgroundColor;
      }

      const body = document.createElement("div");
      body.className = "pin-body";

      const top = document.createElement("div");
      top.className = "pin-top";

      const title = document.createElement("h3");
      title.className = "pin-title";
      title.textContent = pin.title || "(untitled)";

      const actions = document.createElement("div");
      actions.className = "pin-actions";

      const editBtn = document.createElement("button");
      editBtn.type = "button";
      editBtn.className = "btn btn-secondary btn-sm";
      editBtn.textContent = "Edit";
      editBtn.addEventListener("click", function () {
        startEditingPin(pin);
      });

      const delBtn = document.createElement("button");
      delBtn.type = "button";
      delBtn.className = "btn btn-secondary btn-sm";
      delBtn.textContent = "Delete";
      delBtn.addEventListener("click", function () {
        deletePin(pin);
      });

      actions.appendChild(editBtn);
      actions.appendChild(delBtn);
      top.appendChild(title);
      top.appendChild(actions);

      const meta = document.createElement("div");
      meta.className = "pin-meta";
      let metaText = formatDisplayDate(pin.startTime);
      if (pin.durationMinutes != null) {
        metaText += " · " + pin.durationMinutes + " min";
      }
      meta.textContent = metaText;

      body.appendChild(top);
      body.appendChild(meta);

      if (pin.description) {
        const desc = document.createElement("p");
        desc.className = "pin-desc";
        desc.textContent = pin.description;
        body.appendChild(desc);
      }

      const footer = document.createElement("div");
      footer.className = "pin-footer";

      const tags = document.createElement("div");
      tags.className = "pin-tags";

      const iconBadge = document.createElement("span");
      iconBadge.className = "badge icon-badge";
      iconBadge.appendChild(createIconSvg(pin.iconUri, 14));
      const iconText = document.createElement("span");
      iconText.textContent = iconLabelFor(pin.iconUri);
      iconBadge.appendChild(iconText);
      tags.appendChild(iconBadge);

      if (pin.layoutType && pin.layoutType !== "GENERIC_PIN") {
        const layoutBadge = document.createElement("span");
        layoutBadge.className = "badge";
        layoutBadge.textContent = pin.layoutType.replace(/_/g, " ");
        tags.appendChild(layoutBadge);
      }

      const idCode = document.createElement("code");
      idCode.textContent = pin.id;
      footer.appendChild(tags);
      footer.appendChild(idCode);

      body.appendChild(footer);
      item.appendChild(accent);
      item.appendChild(body);
      els.pinsList.appendChild(item);
    });
  }

  async function loadPins() {
    const query = els.includeExpired.checked ? "?includeExpired=true" : "";
    const res = await apiFetch("/ui/api/pins" + query);
    if (!res.ok) {
      if (res.status === 401) {
        await loadSession();
        return;
      }
      flashGlobal(formatApiError(res, "Failed to load pins."), true);
      return;
    }
    renderPins(res.data && res.data.pins ? res.data.pins : []);
  }

  async function loadSession() {
    const res = await apiFetch("/ui/api/session");
    if (!res.ok || !res.data) {
      els.loadingView.classList.add("hidden");
      flashGlobal(formatApiError(res, "Failed to load session."), true);
      return;
    }
    state.revealedTokens = null;
    renderSession(res.data);
    if (res.data.authenticated) {
      await loadPins();
    }
  }

  async function deletePin(pin) {
    if (!window.confirm('Delete pin "' + pin.title + '"? It will be removed from your Pebble timeline on the next sync.')) {
      return;
    }
    const res = await apiFetch("/ui/api/pins/" + encodeURIComponent(pin.id), { method: "DELETE" });
    if (!res.ok) {
      flashGlobal(formatApiError(res, "Failed to delete pin."), true);
      return;
    }
    if (state.editingPinId === pin.id) {
      resetComposer();
    }
    flashGlobal('Deleted "' + pin.title + '".', false);
    await loadPins();
  }

  async function rotateTokens(rotateRead, rotateWrite) {
    const scopeLabel = rotateRead && rotateWrite ? "BOTH read and write tokens"
      : rotateRead ? "your READ token (you will need to re-scan the QR code on your phone)"
      : "your WRITE token (existing scripts will need the new token, your phone stays paired)";
    if (!window.confirm("Rotate " + scopeLabel + "?")) {
      return;
    }
    const res = await apiFetch("/ui/api/tokens/rotate", {
      method: "POST",
      body: JSON.stringify({ rotateRead: rotateRead, rotateWrite: rotateWrite })
    });
    if (!res.ok || !res.data) {
      flashGlobal(formatApiError(res, "Failed to rotate tokens."), true);
      return;
    }
    state.revealedTokens = res.data;
    if (state.session) {
      state.session.tokensAvailable = true;
      renderSession(state.session);
    }
    flashGlobal("Tokens rotated successfully. Copy your updated credentials below.", false);
  }

  els.loginForm.addEventListener("submit", async function (ev) {
    ev.preventDefault();
    showAlert(els.loginError, "", false);
    els.loginSubmit.disabled = true;
    try {
      const res = await apiFetch("/ui/api/login", {
        method: "POST",
        body: JSON.stringify({ token: els.loginToken.value })
      });
      if (!res.ok || !res.data) {
        showAlert(els.loginError, formatApiError(res, "Sign in failed."), true);
        return;
      }
      els.loginToken.value = "";
      state.revealedTokens = null;
      renderSession(res.data);
      await loadPins();
      flashGlobal("Signed in.", false);
    } finally {
      els.loginSubmit.disabled = false;
    }
  });

  els.logoutBtn.addEventListener("click", async function () {
    await apiFetch("/ui/api/logout", { method: "POST" });
    state.revealedTokens = null;
    resetComposer();
    await loadSession();
    flashGlobal("Signed out.", false);
  });

  els.pinForm.addEventListener("submit", async function (ev) {
    ev.preventDefault();
    showAlert(els.pinFormError, "", false);

    const startTime = localInputToRFC3339(els.pinStart.value);
    if (!startTime) {
      showAlert(els.pinFormError, "Please select a valid date and time.", true);
      return;
    }

    const payload = {
      title: els.pinTitle.value.trim(),
      startTime: startTime,
      iconUri: els.pinIcon.value,
      layoutType: els.pinLayout.value
    };
    const desc = els.pinDescription.value.trim();
    if (desc) {
      payload.description = desc;
    }
    const durRaw = els.pinDuration.value.trim();
    if (durRaw !== "") {
      payload.durationMinutes = Number.parseInt(durRaw, 10);
    }
    if (els.pinColor.value) {
      payload.backgroundColor = els.pinColor.value;
    }

    const customId = els.pinId.value.trim();
    let url = "/ui/api/pins";
    let method = "POST";
    if (state.editingPinId) {
      url = "/ui/api/pins/" + encodeURIComponent(state.editingPinId);
      method = "PUT";
      payload.id = state.editingPinId;
    } else if (customId) {
      payload.id = customId;
    }

    els.pinSubmitBtn.disabled = true;
    try {
      const res = await apiFetch(url, {
        method: method,
        body: JSON.stringify(payload)
      });
      if (!res.ok) {
        showAlert(els.pinFormError, formatApiError(res, "Failed to save pin."), true);
        return;
      }
      const actionMsg = state.editingPinId ? 'Updated "' + payload.title + '".' : 'Added "' + payload.title + '".';
      resetComposer();
      flashGlobal(actionMsg, false);
      await loadPins();
    } finally {
      els.pinSubmitBtn.disabled = false;
    }
  });

  els.cancelEditBtn.addEventListener("click", resetComposer);
  els.pinResetBtn.addEventListener("click", resetComposer);
  els.pinTitle.addEventListener("input", updateCounters);
  els.pinDescription.addEventListener("input", updateCounters);

  els.durationPresets.addEventListener("click", function (ev) {
    const target = ev.target;
    if (target && target.dataset && target.dataset.duration !== undefined) {
      els.pinDuration.value = target.dataset.duration;
    }
  });

  els.includeExpired.addEventListener("change", loadPins);
  els.refreshPinsBtn.addEventListener("click", loadPins);

  els.profileForm.addEventListener("submit", async function (ev) {
    ev.preventDefault();
    const res = await apiFetch("/ui/api/profile", {
      method: "PUT",
      body: JSON.stringify({ name: els.profileName.value })
    });
    if (!res.ok || !res.data) {
      flashGlobal(formatApiError(res, "Failed to update feed name."), true);
      return;
    }
    renderSession(res.data);
    flashGlobal("Feed name updated.", false);
  });

  els.qrToggleBtn.addEventListener("click", toggleQR);

  els.revealTokensBtn.addEventListener("click", async function () {
    const res = await apiFetch("/ui/api/tokens/reveal", { method: "POST" });
    if (!res.ok || !res.data) {
      flashGlobal(formatApiError(res, "Failed to reveal tokens."), true);
      return;
    }
    if (!res.data.available) {
      flashGlobal("Tokens are not sealed for this legacy account yet — use Rotate Tokens below.", true);
      return;
    }
    state.revealedTokens = res.data;
    renderTokensPanel();
  });

  els.hideTokensBtn.addEventListener("click", function () {
    state.revealedTokens = null;
    renderTokensPanel();
  });

  els.rotateWriteBtn.addEventListener("click", function () { rotateTokens(false, true); });
  els.rotateReadBtn.addEventListener("click", function () { rotateTokens(true, false); });
  els.rotateBothBtn.addEventListener("click", function () { rotateTokens(true, true); });

  els.copyFeedUrlBtn.addEventListener("click", function () { copyText(els.feedUrlDisplay.textContent, "Feed URL"); });
  els.copyUserIdBtn.addEventListener("click", function () { copyText(els.userIdDisplay.textContent, "User ID"); });
  els.copyApiUrlBtn.addEventListener("click", function () { copyText(els.pinsApiUrlDisplay.textContent, "Pins API URL"); });
  els.copyWriteTokenBtn.addEventListener("click", function () { copyText(els.writeTokenDisplay.textContent, "Write API Token"); });
  els.copyReadTokenBtn.addEventListener("click", function () { copyText(els.readTokenDisplay.textContent, "Read API Token"); });
  els.copyCurlBtn.addEventListener("click", function () { copyText(els.curlExample.textContent, "curl command"); });
  els.copyLegacyFeedBtn.addEventListener("click", function () { copyText(els.legacyFeedUrl.textContent, "Feed URL"); });
  els.copyLegacyApiBtn.addEventListener("click", function () { copyText(els.legacyApiUrl.textContent, "Pins API URL"); });

  initIconPicker();
  initColorSwatches();
  resetComposer();
  loadSession();
})();
`
