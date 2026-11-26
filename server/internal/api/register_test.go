package api

import (
	"bytes"
	"html"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/url"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
	"time"

	"github.com/gabrielhidasy/PebblePinManager/server/internal/store"
)

func newRegisterTestServer(t *testing.T, trustProxy bool, reg RegistrationConfig) (*Server, *bytes.Buffer) {
	t.Helper()
	st, err := store.New(filepath.Join(t.TempDir(), "pins.json"), 48*time.Hour)
	if err != nil {
		t.Fatalf("store.New: %v", err)
	}
	var logBuf bytes.Buffer
	logger := slog.New(slog.NewJSONHandler(&logBuf, nil))
	auth := NewAuthenticator(testWriteToken, testReadToken)
	srv := NewServer(
		st,
		auth,
		WithLogger(logger),
		WithTrustProxy(trustProxy),
		WithRegistration(reg),
	)
	return srv, &logBuf
}

func TestRegister_DisabledByDefaultReturns404(t *testing.T) {
	env := newTestEnv(t, 48*time.Hour)

	rec := env.do(t, http.MethodGet, "/register", "", "")
	if rec.Code != http.StatusNotFound {
		t.Fatalf("GET /register when disabled = %d, want 404", rec.Code)
	}
	headRec := env.do(t, http.MethodHead, "/register", "", "")
	if headRec.Code != http.StatusNotFound {
		t.Fatalf("HEAD /register when disabled = %d, want 404", headRec.Code)
	}
}

func TestRegister_EnabledServesQRPageAndNeverLogsToken(t *testing.T) {
	srv, logs := newRegisterTestServer(t, true, RegistrationConfig{
		Enabled:    true,
		Token:      testReadToken,
		TokenScope: "read",
		PublicURL:  "https://pins.example.com",
		ServerName: "Home Pin Server",
	})

	req := httptest.NewRequest(http.MethodGet, "/register", nil)
	rec := httptest.NewRecorder()
	srv.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("GET /register = %d, want 200 (body=%s)", rec.Code, rec.Body.String())
	}
	if ct := rec.Header().Get("Content-Type"); !strings.HasPrefix(ct, "text/html") {
		t.Errorf("Content-Type = %q, want text/html", ct)
	}
	if cc := rec.Header().Get("Cache-Control"); !strings.Contains(cc, "no-store") {
		t.Errorf("Cache-Control = %q, want no-store", cc)
	}
	if robots := rec.Header().Get("X-Robots-Tag"); !strings.Contains(robots, "noindex") {
		t.Errorf("X-Robots-Tag = %q, want noindex", robots)
	}
	if csp := rec.Header().Get("Content-Security-Policy"); !strings.Contains(csp, "default-src 'none'") {
		t.Errorf("CSP = %q, want strict default-src 'none'", csp)
	}

	body := rec.Body.String()
	if !strings.Contains(body, "<svg ") || !strings.Contains(body, "</svg>") {
		t.Fatalf("expected inline SVG QR code in /register page")
	}
	if !strings.Contains(body, "https://pins.example.com/pins.json") {
		t.Errorf("expected feed URL in /register page")
	}
	if !strings.Contains(body, "Home Pin Server") {
		t.Errorf("expected server name in /register page")
	}
	if !strings.Contains(body, "Read-Only Token") {
		t.Errorf("expected Read-Only Token badge")
	}
	if strings.Contains(body, "Least-privilege recommendation") {
		t.Errorf("did not expect write-token warning for read-only token")
	}

	// Verify pairing href matches expected pebblepin://pair URI.
	hrefRe := regexp.MustCompile(`href="(pebblepin://pair\?[^"]+)"`)
	m := hrefRe.FindStringSubmatch(body)
	if len(m) != 2 {
		t.Fatalf("missing pebblepin://pair href in page")
	}
	rawURI := html.UnescapeString(m[1])
	parsed, err := url.Parse(rawURI)
	if err != nil {
		t.Fatalf("parse pairing URI: %v", err)
	}
	q := parsed.Query()
	if q.Get("v") != "1" || q.Get("auth") != "bearer" || q.Get("token") != testReadToken ||
		q.Get("url") != "https://pins.example.com/pins.json" || q.Get("name") != "Home Pin Server" ||
		q.Get("allowHttp") != "" {
		t.Fatalf("unexpected pairing query params: %v", q)
	}

	// HEAD /register returns headers without body.
	headReq := httptest.NewRequest(http.MethodHead, "/register", nil)
	headRec := httptest.NewRecorder()
	srv.ServeHTTP(headRec, headReq)
	if headRec.Code != http.StatusOK || headRec.Body.Len() != 0 {
		t.Fatalf("HEAD /register status=%d bodyLen=%d", headRec.Code, headRec.Body.Len())
	}

	// Ensure access logs never leak the token.
	if strings.Contains(logs.String(), testReadToken) || strings.Contains(logs.String(), testWriteToken) {
		t.Fatalf("token leaked into logs: %s", logs.String())
	}
}

func TestRegister_InfersURLFromProxyHeadersAndWarnsOnWriteTokenOrLocalhost(t *testing.T) {
	srv, _ := newRegisterTestServer(t, true, RegistrationConfig{
		Enabled:    true,
		Token:      testWriteToken,
		TokenScope: "write",
	})

	// Case 1: Reverse proxy HTTPS headers.
	req := httptest.NewRequest(http.MethodGet, "http://127.0.0.1:8080/register", nil)
	req.Header.Set("X-Forwarded-Proto", "https")
	req.Header.Set("X-Forwarded-Host", "pins.myhome.example")
	rec := httptest.NewRecorder()
	srv.ServeHTTP(rec, req)

	body := rec.Body.String()
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", rec.Code)
	}
	if !strings.Contains(body, "https://pins.myhome.example/pins.json") {
		t.Errorf("expected inferred HTTPS proxy URL in page")
	}
	if !strings.Contains(body, "Least-privilege recommendation") {
		t.Errorf("expected write-token least-privilege warning")
	}
	if strings.Contains(body, "Localhost address detected") {
		t.Errorf("did not expect localhost warning for proxy host")
	}

	// Case 2: Direct localhost HTTP request -> localhost + HTTP warnings + allowHttp=1 in URI.
	localReq := httptest.NewRequest(http.MethodGet, "http://127.0.0.1:8080/register", nil)
	localRec := httptest.NewRecorder()
	srv.ServeHTTP(localRec, localReq)
	localBody := localRec.Body.String()
	if !strings.Contains(localBody, "Localhost address detected") {
		t.Errorf("expected localhost warning")
	}
	if !strings.Contains(localBody, "Unencrypted HTTP feed URL") {
		t.Errorf("expected HTTP warning")
	}
	if !strings.Contains(localBody, "allowHttp=1") {
		t.Errorf("expected allowHttp=1 in HTTP pairing URI")
	}
}

func TestRegister_IgnoresForwardedHeadersWhenTrustProxyFalse(t *testing.T) {
	srv, _ := newRegisterTestServer(t, false, RegistrationConfig{
		Enabled:    true,
		Token:      testReadToken,
		TokenScope: "read",
	})

	req := httptest.NewRequest(http.MethodGet, "http://10.0.0.5:8080/register", nil)
	req.Header.Set("X-Forwarded-Proto", "https")
	req.Header.Set("X-Forwarded-Host", "spoofed.attacker.example")
	rec := httptest.NewRecorder()
	srv.ServeHTTP(rec, req)

	body := rec.Body.String()
	if strings.Contains(body, "spoofed.attacker.example") {
		t.Fatalf("must not trust X-Forwarded-Host when TrustProxy is false")
	}
	if !strings.Contains(body, "http://10.0.0.5:8080/pins.json") {
		t.Fatalf("expected direct request host URL in page")
	}
}
