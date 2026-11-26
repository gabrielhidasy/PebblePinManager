package api

import (
	"bytes"
	"html/template"
	"net"
	"net/http"
	"net/url"
	"strings"

	"github.com/gabrielhidasy/PebblePinManager/server/internal/qrcode"
)

// RegistrationConfig controls the opt-in /register QR pairing endpoint.
type RegistrationConfig struct {
	Enabled    bool
	Token      string
	TokenScope string // "read" or "write"
	PublicURL  string // Optional base URL (e.g. https://pins.example.com)
	ServerName string // Optional friendly name embedded in pairing payload
}

// WithRegistration enables the /register QR pairing page when cfg.Enabled is true.
func WithRegistration(cfg RegistrationConfig) ServerOption {
	return func(s *Server) {
		if cfg.Enabled {
			s.registration = cfg
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

func (s *Server) handleRegister(w http.ResponseWriter, r *http.Request) {
	feedURL, hostName := s.resolveRegistrationFeedURL(r)
	pairingURI := BuildPairingURI(feedURL, s.registration.Token, s.registration.ServerName)

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
		ServerName:   strings.TrimSpace(s.registration.ServerName),
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

func (s *Server) resolveRegistrationFeedURL(r *http.Request) (feedURL string, hostName string) {
	if base := strings.TrimRight(strings.TrimSpace(s.registration.PublicURL), "/"); base != "" {
		if strings.HasSuffix(base, "/pins.json") {
			feedURL = base
		} else {
			feedURL = base + "/pins.json"
		}
		if parsed, err := url.Parse(feedURL); err == nil {
			hostName = parsed.Hostname()
		}
		return feedURL, hostName
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

	feedURL = scheme + "://" + host + "/pins.json"
	if parsed, err := url.Parse(feedURL); err == nil {
		hostName = parsed.Hostname()
	}
	return feedURL, hostName
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

type registerPageData struct {
	ServerName   string
	FeedURL      string
	TokenScope   string
	IsWriteToken bool
	IsHTTP       bool
	IsLocalhost  bool
	QRSVG        template.HTML
	PairingHref  template.URL
}

var registerPageTemplate = template.Must(template.New("register").Parse(`<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="robots" content="noindex,nofollow">
  <title>Pair Pin Manager for Pebble</title>
  <style>
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
      max-width: 520px;
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
    .subtitle {
      margin: 0 0 20px;
      color: var(--muted);
      font-size: 0.94rem;
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
      justify-content: center;
      margin-bottom: 20px;
    }
    .cta-btn {
      display: inline-block;
      padding: 10px 16px;
      border-radius: 10px;
      background: var(--accent);
      color: var(--accent-fg);
      text-decoration: none;
      font-weight: 600;
      font-size: 0.92rem;
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
  </style>
</head>
<body>
  <main>
    <h1>Pair Pin Manager for Pebble</h1>
    <p class="subtitle">Scan this QR code in the Android app to pair your phone with this companion pin server.</p>

    <div class="qr-box">{{.QRSVG}}</div>

    <div class="cta-row">
      <a class="cta-btn" href="{{.PairingHref}}">Open in Pin Manager for Pebble</a>
    </div>

    <ol>
      <li>Open <strong>Pin Manager for Pebble</strong> on your Android phone.</li>
      <li>Go to the <strong>Feeds</strong> tab and tap <strong>Scan QR</strong>.</li>
      <li>Point your camera at the QR code above to pair and sync automatically.</li>
    </ol>

    <table class="details">
      {{if .ServerName}}
      <tr>
        <th>Server Name</th>
        <td>{{.ServerName}}</td>
      </tr>
      {{end}}
      <tr>
        <th>Feed URL</th>
        <td><code>{{.FeedURL}}</code></td>
      </tr>
      <tr>
        <th>Authentication</th>
        <td>Bearer Token</td>
      </tr>
      <tr>
        <th>Token Scope</th>
        <td>
          {{if .IsWriteToken}}
            <span class="badge badge-warn">Read-Write Token</span>
          {{else}}
            <span class="badge badge-ok">Read-Only Token</span>
          {{end}}
        </td>
      </tr>
    </table>

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
      This page embeds your server access token. Once your phone is paired, disable <code>PIN_SERVER_REGISTRATION_MODE</code> (set it to <code>false</code> or unset it) and restart the server.
    </div>
  </main>
</body>
</html>
`))
