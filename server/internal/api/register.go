package api

import (
	"bytes"
	"html/template"
	"io"
	"mime"
	"net"
	"net/http"
	"net/url"
	"strings"

	"github.com/gabrielhidasy/PebblePinManager/server/internal/qrcode"
)

// RegistrationConfig controls the opt-in /register QR pairing endpoint.
type RegistrationConfig struct {
	Enabled    bool
	Token      string // Optional legacy static token (only used when UserManager is not configured)
	TokenScope string // "read" or "write" (legacy fallback only)
	PublicURL  string // Optional base URL (e.g. https://pins.example.com)
	ServerName string // Optional friendly name embedded in pairing payload
}

// WithRegistration enables the /register QR pairing page when cfg.Enabled is true.
func WithRegistration(cfg RegistrationConfig) ServerOption {
	return func(s *Server) {
		if cfg.Enabled {
			s.registration = cfg
		}
		if trimmed := strings.TrimSpace(cfg.PublicURL); trimmed != "" && s.publicURL == "" {
			s.publicURL = trimmed
		}
		if trimmed := strings.TrimSpace(cfg.ServerName); trimmed != "" && s.serverName == "" {
			s.serverName = trimmed
		}
	}
}

// BuildPairingURI constructs the canonical pebblepin://pair URI understood by the Android app.
func BuildPairingURI(feedURL, token, serverName string) string {
	q := url.Values{}
	q.Set("v", "1")
	q.Set("url", feedURL)
	q.Set("auth", "bearer")
	q.Set("token", token)
	if name := strings.TrimSpace(serverName); name != "" {
		q.Set("name", name)
	}
	if strings.HasPrefix(strings.ToLower(feedURL), "http://") {
		q.Set("allowHttp", "1")
	}
	// Use RFC 3986 percent-encoding (%20 instead of '+') for clean QR & HTML href output.
	encoded := strings.ReplaceAll(q.Encode(), "+", "%20")
	return "pebblepin://pair?" + encoded
}

func (s *Server) effectivePublicURL() string {
	if trimmed := strings.TrimSpace(s.publicURL); trimmed != "" {
		return trimmed
	}
	return strings.TrimSpace(s.registration.PublicURL)
}

func (s *Server) effectiveServerName() string {
	if trimmed := strings.TrimSpace(s.serverName); trimmed != "" {
		return trimmed
	}
	return strings.TrimSpace(s.registration.ServerName)
}

func (s *Server) handleRegisterGet(w http.ResponseWriter, r *http.Request) {
	if s.users == nil {
		s.handleLegacyRegister(w, r)
		return
	}

	// When the Web UI is enabled and an authenticated reverse-proxy identity (e.g. Authelia
	// Remote-User) is present, send the user straight to their persistent per-user dashboard
	// and QR pairing card instead of creating duplicate anonymous users.
	if s.webUI.Enabled {
		if _, _, ok := s.extractProxyIdentity(r); ok {
			w.Header().Set("Cache-Control", "no-store, max-age=0")
			http.Redirect(w, r, "/#pair", http.StatusSeeOther)
			return
		}
	}

	baseURL, hostName := s.resolveBaseURL(r)
	data := registerFormPageData{
		ServerName:  s.effectiveServerName(),
		BaseURL:     baseURL,
		IsHTTP:      strings.HasPrefix(strings.ToLower(baseURL), "http://"),
		IsLocalhost: isLoopbackHost(hostName),
	}

	var buf bytes.Buffer
	if err := registerFormTemplate.Execute(&buf, data); err != nil {
		s.logger.Error("register_form_template_failed", "err", err)
		writeError(w, r, http.StatusInternalServerError, "internal_error", "Failed to render registration page.")
		return
	}

	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store, max-age=0")
	w.Header().Set("Pragma", "no-cache")
	w.Header().Set("X-Robots-Tag", "noindex, nofollow")
	w.Header().Set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; img-src 'self' data:; form-action 'self'")
	w.WriteHeader(http.StatusOK)
	if r.Method != http.MethodHead {
		_, _ = w.Write(buf.Bytes())
	}
}

func (s *Server) handleRegisterPost(w http.ResponseWriter, r *http.Request) {
	var name string
	wantsJSON := requestPrefersJSON(r)

	ct := strings.TrimSpace(r.Header.Get("Content-Type"))
	if ct != "" {
		mediaType, _, err := mime.ParseMediaType(ct)
		if err != nil {
			writeError(w, r, http.StatusUnsupportedMediaType, "unsupported_media_type", "Content-Type must be application/x-www-form-urlencoded or application/json.")
			return
		}
		switch mediaType {
		case "application/json":
			wantsJSON = true
			payload, ok := s.decodeCreateUserJSON(w, r)
			if !ok {
				return
			}
			name = payload.Name
		case "application/x-www-form-urlencoded":
			r.Body = http.MaxBytesReader(w, r.Body, maxUserRequestBodyBytes)
			raw, err := io.ReadAll(r.Body)
			if err != nil {
				writeError(w, r, http.StatusBadRequest, "invalid_body", "Unable to read registration form.")
				return
			}
			vals, err := url.ParseQuery(string(raw))
			if err != nil {
				writeError(w, r, http.StatusBadRequest, "invalid_form", "Malformed form payload.")
				return
			}
			name = vals.Get("name")
		default:
			writeError(w, r, http.StatusUnsupportedMediaType, "unsupported_media_type", "Content-Type must be application/x-www-form-urlencoded or application/json.")
			return
		}
	}

	created, err := s.users.CreateUser(name)
	if err != nil {
		s.writeCreateUserError(w, r, err)
		return
	}

	baseURL, hostName := s.resolveBaseURL(r)
	resp := s.buildUserCreatedResponse(baseURL, created)

	w.Header().Set("Cache-Control", "no-store, max-age=0")
	w.Header().Set("Pragma", "no-cache")
	w.Header().Set("X-Robots-Tag", "noindex, nofollow")

	if wantsJSON {
		w.Header().Set("Location", "/api/v1/users/"+created.User.ID)
		writeJSON(w, r, http.StatusCreated, resp)
		return
	}

	code, err := qrcode.Encode(resp.PairingURI)
	if err != nil {
		s.logger.Error("register_qr_encode_failed", "err", err)
		writeError(w, r, http.StatusInternalServerError, "qr_encode_failed", "Unable to generate pairing QR code.")
		return
	}

	displayName := created.User.Name
	if displayName == "" {
		displayName = s.effectiveServerName()
	}

	data := registerPageData{
		MultiUser:    true,
		WebUIEnabled: s.webUI.Enabled,
		UserID:       created.User.ID,
		ServerName:   displayName,
		FeedURL:      resp.FeedURL,
		PinsAPIURL:   resp.PinsAPIURL,
		WriteToken:   created.WriteToken,
		TokenScope:   "read",
		IsWriteToken: false,
		IsHTTP:       strings.HasPrefix(strings.ToLower(resp.FeedURL), "http://"),
		IsLocalhost:  isLoopbackHost(hostName),
		QRSVG:        template.HTML(code.SVG(4)),
		PairingHref:  template.URL(resp.PairingURI),
	}

	var buf bytes.Buffer
	if err := registerPageTemplate.Execute(&buf, data); err != nil {
		s.logger.Error("register_template_failed", "err", err)
		writeError(w, r, http.StatusInternalServerError, "internal_error", "Failed to render registration page.")
		return
	}

	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; img-src 'self' data:")
	w.WriteHeader(http.StatusCreated)
	_, _ = w.Write(buf.Bytes())
}

func (s *Server) handleLegacyRegister(w http.ResponseWriter, r *http.Request) {
	baseURL, hostName := s.resolveBaseURL(r)
	feedURL := baseURL + "/pins.json"
	pairingURI := BuildPairingURI(feedURL, s.registration.Token, s.effectiveServerName())

	code, err := qrcode.Encode(pairingURI)
	if err != nil {
		s.logger.Error("register_qr_encode_failed", "err", err)
		writeError(w, r, http.StatusInternalServerError, "qr_encode_failed", "Unable to generate pairing QR code.")
		return
	}

	isHTTP := strings.HasPrefix(strings.ToLower(feedURL), "http://")
	isLocalhost := isLoopbackHost(hostName)
	scope := s.registration.TokenScope
	if scope == "" {
		scope = "read"
	}

	data := registerPageData{
		ServerName:   s.effectiveServerName(),
		FeedURL:      feedURL,
		TokenScope:   scope,
		IsWriteToken: scope == "write",
		IsHTTP:       isHTTP,
		IsLocalhost:  isLocalhost,
		QRSVG:        template.HTML(code.SVG(4)),
		PairingHref:  template.URL(pairingURI),
	}

	var buf bytes.Buffer
	if err := registerPageTemplate.Execute(&buf, data); err != nil {
		s.logger.Error("register_template_failed", "err", err)
		writeError(w, r, http.StatusInternalServerError, "internal_error", "Failed to render registration page.")
		return
	}

	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store, max-age=0")
	w.Header().Set("Pragma", "no-cache")
	w.Header().Set("X-Robots-Tag", "noindex, nofollow")
	w.Header().Set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; img-src 'self' data:")
	w.WriteHeader(http.StatusOK)
	if r.Method != http.MethodHead {
		_, _ = w.Write(buf.Bytes())
	}
}

func (s *Server) resolveBaseURL(r *http.Request) (baseURL string, hostName string) {
	if base := strings.TrimRight(s.effectivePublicURL(), "/"); base != "" {
		base = strings.TrimSuffix(base, "/pins.json")
		if parsed, err := url.Parse(base); err == nil {
			hostName = parsed.Hostname()
		}
		return base, hostName
	}

	scheme := "http"
	if r.TLS != nil {
		scheme = "https"
	}
	host := strings.TrimSpace(r.Host)

	if s.trustProxy {
		if xfProto := firstCSVHeader(r.Header.Get("X-Forwarded-Proto")); xfProto != "" {
			lower := strings.ToLower(xfProto)
			if lower == "https" || lower == "http" {
				scheme = lower
			}
		}
		if xfHost := firstCSVHeader(r.Header.Get("X-Forwarded-Host")); xfHost != "" && isSafeHostHeader(xfHost) {
			host = xfHost
		}
	}

	if host == "" {
		host = "localhost:8080"
	}

	baseURL = scheme + "://" + host
	if parsed, err := url.Parse(baseURL); err == nil {
		hostName = parsed.Hostname()
	}
	return baseURL, hostName
}

func requestPrefersJSON(r *http.Request) bool {
	accept := strings.ToLower(r.Header.Get("Accept"))
	if strings.Contains(accept, "application/json") && !strings.Contains(accept, "text/html") {
		return true
	}
	return false
}

func firstCSVHeader(raw string) string {
	first, _, _ := strings.Cut(raw, ",")
	return strings.TrimSpace(first)
}

func isSafeHostHeader(host string) bool {
	if host == "" {
		return false
	}
	for _, r := range host {
		if r <= 0x20 || r == '/' || r == '\\' || r == '@' || r == '?' || r == '#' {
			return false
		}
	}
	return true
}

func isLoopbackHost(host string) bool {
	h := strings.ToLower(strings.TrimSpace(host))
	if h == "localhost" {
		return true
	}
	ip := net.ParseIP(h)
	return ip != nil && ip.IsLoopback()
}

type registerFormPageData struct {
	ServerName  string
	BaseURL     string
	IsHTTP      bool
	IsLocalhost bool
}

type registerPageData struct {
	MultiUser    bool
	WebUIEnabled bool
	UserID       string
	ServerName   string
	FeedURL      string
	PinsAPIURL   string
	WriteToken   string
	TokenScope   string
	IsWriteToken bool
	IsHTTP       bool
	IsLocalhost  bool
	QRSVG        template.HTML
	PairingHref  template.URL
}

const registerSharedCSS = `
    :root {
      color-scheme: light dark;
      --bg: #f4f6f8;
      --card: #ffffff;
      --fg: #182026;
      --muted: #5c7080;
      --border: #dce3ea;
      --accent: #0f62fe;
      --accent-fg: #ffffff;
      --warn-bg: #fff3cd;
      --warn-border: #f0b429;
      --warn-fg: #664d03;
      --ok-bg: #e6f4ea;
      --ok-fg: #137333;
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
        --accent-fg: #0b1220;
        --warn-bg: #3b2d0a;
        --warn-border: #d99b1e;
        --warn-fg: #ffe08a;
        --ok-bg: #123321;
        --ok-fg: #81c995;
        --code-bg: #111820;
      }
    }
    * { box-sizing: border-box; }
    body {
      margin: 0;
      padding: 24px 16px 40px;
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
      background: var(--bg);
      color: var(--fg);
      line-height: 1.5;
    }
    main {
      max-width: 560px;
      margin: 0 auto;
      background: var(--card);
      border: 1px solid var(--border);
      border-radius: 16px;
      padding: 24px;
      box-shadow: 0 6px 24px rgba(0, 0, 0, 0.08);
    }
    h1 {
      margin: 0 0 6px;
      font-size: 1.35rem;
      line-height: 1.25;
    }
    h2 {
      margin: 20px 0 8px;
      font-size: 1rem;
    }
    .subtitle {
      margin: 0 0 20px;
      color: var(--muted);
      font-size: 0.94rem;
    }
    .field {
      margin-bottom: 16px;
    }
    .field label {
      display: block;
      font-weight: 600;
      font-size: 0.9rem;
      margin-bottom: 6px;
    }
    .field input[type="text"] {
      width: 100%;
      padding: 10px 12px;
      border-radius: 10px;
      border: 1px solid var(--border);
      background: var(--bg);
      color: var(--fg);
      font-size: 0.95rem;
    }
    .hint {
      margin-top: 6px;
      color: var(--muted);
      font-size: 0.84rem;
    }
    .qr-box {
      display: flex;
      justify-content: center;
      padding: 16px;
      margin: 0 auto 20px;
      background: #ffffff;
      border-radius: 12px;
      border: 1px solid var(--border);
      max-width: 320px;
    }
    .qr-box svg {
      display: block;
      width: 100%;
      max-width: 280px;
      height: auto;
    }
    .cta-row {
      display: flex;
      flex-wrap: wrap;
      gap: 10px;
      justify-content: center;
      margin-bottom: 20px;
    }
    .cta-btn {
      display: inline-block;
      padding: 10px 16px;
      border-radius: 10px;
      border: 0;
      background: var(--accent);
      color: var(--accent-fg);
      text-decoration: none;
      font-weight: 600;
      font-size: 0.92rem;
      cursor: pointer;
    }
    .cta-btn-secondary {
      background: transparent;
      color: var(--fg);
      border: 1px solid var(--border);
    }
    .details {
      width: 100%;
      border-collapse: collapse;
      margin-bottom: 18px;
      font-size: 0.9rem;
    }
    .details th, .details td {
      text-align: left;
      padding: 8px 0;
      border-bottom: 1px solid var(--border);
      vertical-align: top;
    }
    .details th {
      width: 34%;
      color: var(--muted);
      font-weight: 600;
      padding-right: 12px;
    }
    .details td {
      word-break: break-all;
    }
    code {
      background: var(--code-bg);
      padding: 2px 6px;
      border-radius: 5px;
      font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
      font-size: 0.85em;
    }
    pre {
      background: var(--code-bg);
      padding: 12px;
      border-radius: 10px;
      overflow-x: auto;
      font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
      font-size: 0.8rem;
      line-height: 1.45;
      margin: 0 0 18px;
    }
    .badge {
      display: inline-block;
      padding: 2px 8px;
      border-radius: 999px;
      font-size: 0.8rem;
      font-weight: 600;
    }
    .badge-ok { background: var(--ok-bg); color: var(--ok-fg); }
    .badge-warn { background: var(--warn-bg); color: var(--warn-fg); }
    .notice {
      padding: 12px 14px;
      border-radius: 10px;
      border: 1px solid var(--warn-border);
      background: var(--warn-bg);
      color: var(--warn-fg);
      font-size: 0.88rem;
      margin-bottom: 12px;
    }
    .notice strong { display: block; margin-bottom: 2px; }
    ol {
      margin: 0 0 18px;
      padding-left: 20px;
      font-size: 0.92rem;
    }
    ol li { margin-bottom: 6px; }
`

var registerFormTemplate = template.Must(template.New("register-form").Parse(`<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="robots" content="noindex,nofollow">
  <title>Register — Pin Manager for Pebble</title>
  <style>` + registerSharedCSS + `</style>
</head>
<body>
  <main>
    <h1>Register a Pebble Pin Feed</h1>
    <p class="subtitle">Create an isolated user feed at <code>{{.BaseURL}}/&lt;random-id&gt;/pins.json</code> with dedicated per-user read and write tokens.</p>

    <form method="POST" action="/register">
      <div class="field">
        <label for="name">Feed / Device Name (optional)</label>
        <input id="name" name="name" type="text" maxlength="80" placeholder="{{if .ServerName}}{{.ServerName}}{{else}}My Pebble{{end}}" value="{{.ServerName}}">
        <div class="hint">Displayed as the feed label in the Android app after scanning the QR code.</div>
      </div>
      <div class="cta-row">
        <button class="cta-btn" type="submit">Create User &amp; Generate Pairing QR</button>
      </div>
    </form>

    {{if .IsLocalhost}}
    <div class="notice">
      <strong>Localhost address detected</strong>
      Your phone cannot reach <code>localhost</code> or <code>127.0.0.1</code>. Open this page using your public/LAN hostname or set <code>PIN_SERVER_PUBLIC_URL</code>.
    </div>
    {{end}}

    {{if .IsHTTP}}
    <div class="notice">
      <strong>Unencrypted HTTP feed URL</strong>
      Only use plain HTTP on a trusted local network. For internet deployments, terminate HTTPS in a reverse proxy and set <code>PIN_SERVER_TRUST_PROXY=true</code> or <code>PIN_SERVER_PUBLIC_URL=https://...</code>.
    </div>
    {{end}}

    <div class="notice">
      <strong>Registration mode is active</strong>
      Anyone who can reach <code>/register</code> can create a new user feed. Once all users are paired, disable <code>PIN_SERVER_REGISTRATION_MODE</code> (set it to <code>false</code> or unset it) and restart the server.
    </div>
  </main>
</body>
</html>
`))

var registerPageTemplate = template.Must(template.New("register").Parse(`<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="robots" content="noindex,nofollow">
  <title>Pair Pin Manager for Pebble</title>
  <style>` + registerSharedCSS + `</style>
</head>
<body>
  <main>
    <h1>Pair Pin Manager for Pebble</h1>
    <p class="subtitle">Scan this QR code in the Android app to pair your phone with {{if .MultiUser}}your dedicated pin feed{{else}}this companion pin server{{end}}.</p>

    <div class="qr-box">{{.QRSVG}}</div>

    <div class="cta-row">
      <a class="cta-btn" href="{{.PairingHref}}">Open in Pin Manager for Pebble</a>
      {{if and .MultiUser .WebUIEnabled}}<a class="cta-btn cta-btn-secondary" href="/">Open Web UI</a>{{end}}
      {{if .MultiUser}}<a class="cta-btn cta-btn-secondary" href="/register">Register Another User</a>{{end}}
    </div>

    <ol>
      <li>Open <strong>Pin Manager for Pebble</strong> on your Android phone.</li>
      <li>Go to the <strong>Feeds</strong> tab and tap <strong>Scan QR</strong>.</li>
      <li>Point your camera at the QR code above to pair and sync automatically.</li>
    </ol>

    <table class="details">
      {{if .MultiUser}}
      <tr>
        <th>User ID</th>
        <td><code>{{.UserID}}</code></td>
      </tr>
      {{end}}
      {{if .ServerName}}
      <tr>
        <th>{{if .MultiUser}}Feed Name{{else}}Server Name{{end}}</th>
        <td>{{.ServerName}}</td>
      </tr>
      {{end}}
      <tr>
        <th>Feed URL</th>
        <td><code>{{.FeedURL}}</code></td>
      </tr>
      {{if .MultiUser}}
      <tr>
        <th>Pins API URL</th>
        <td><code>{{.PinsAPIURL}}</code></td>
      </tr>
      {{end}}
      <tr>
        <th>Authentication</th>
        <td>Bearer Token</td>
      </tr>
      <tr>
        <th>QR Token Scope</th>
        <td>
          {{if .IsWriteToken}}
            <span class="badge badge-warn">Read-Write Token</span>
          {{else if .MultiUser}}
            <span class="badge badge-ok">Read-Only Token (Per-User)</span>
          {{else}}
            <span class="badge badge-ok">Read-Only Token</span>
          {{end}}
        </td>
      </tr>
      {{if .MultiUser}}
      <tr>
        <th>Write API Token</th>
        <td><code>{{.WriteToken}}</code></td>
      </tr>
      {{end}}
    </table>

    {{if .MultiUser}}
    <h2>Push Pins to This User</h2>
    <pre>curl -sf -X POST "{{.PinsAPIURL}}" \
  -H "Authorization: Bearer {{.WriteToken}}" \
  -H "Content-Type: application/json" \
  -d '{"title":"Hello Pebble","startTime":"2026-11-27T14:00:00Z"}'</pre>

    <div class="notice">
      <strong>Save your Write API Token now</strong>
      Per-user tokens are stored only as SHA-256 hashes on the server and cannot be recovered after you leave this page.
    </div>
    {{end}}

    {{if .IsLocalhost}}
    <div class="notice">
      <strong>Localhost address detected</strong>
      Your phone cannot reach <code>localhost</code> or <code>127.0.0.1</code>. Open this page using your public/LAN hostname or set <code>PIN_SERVER_PUBLIC_URL</code>.
    </div>
    {{end}}

    {{if .IsHTTP}}
    <div class="notice">
      <strong>Unencrypted HTTP feed URL</strong>
      Only use plain HTTP on a trusted local network. For internet deployments, terminate HTTPS in a reverse proxy and set <code>PIN_SERVER_TRUST_PROXY=true</code> or <code>PIN_SERVER_PUBLIC_URL=https://...</code>.
    </div>
    {{end}}

    {{if .IsWriteToken}}
    <div class="notice">
      <strong>Least-privilege recommendation</strong>
      This QR code uses your read-write token because <code>PIN_SERVER_READ_TOKEN</code> is not set. Configure a separate <code>PIN_SERVER_READ_TOKEN</code> for phone clients.
    </div>
    {{end}}

    <div class="notice">
      <strong>Registration mode is active</strong>
      Once pairing is complete, disable <code>PIN_SERVER_REGISTRATION_MODE</code> (set it to <code>false</code> or unset it) and restart the server.
    </div>
  </main>
</body>
</html>
`))
