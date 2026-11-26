package api

import (
	"bytes"
	"encoding/json"
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

func newMultiUserRegisterTestServer(t *testing.T, trustProxy bool, maxUsers int, reg RegistrationConfig) (*Server, *store.UserManager, *bytes.Buffer) {
	t.Helper()
	dir := t.TempDir()
	now := time.Date(2026, 11, 26, 12, 0, 0, 0, time.UTC)
	clock := func() time.Time { return now }

	st, err := store.New(filepath.Join(dir, "pins.json"), 48*time.Hour, store.WithClock(clock))
	if err != nil {
		t.Fatalf("store.New: %v", err)
	}
	mgr, err := store.NewUserManager(dir, 48*time.Hour, store.WithUserClock(clock), store.WithMaxUsers(maxUsers))
	if err != nil {
		t.Fatalf("store.NewUserManager: %v", err)
	}
	var logBuf bytes.Buffer
	logger := slog.New(slog.NewJSONHandler(&logBuf, nil))
	auth := NewAuthenticator(testWriteToken, testReadToken)
	srv := NewServer(
		st,
		auth,
		WithLogger(logger),
		WithTrustProxy(trustProxy),
		WithServerClock(clock),
		WithUserManager(mgr),
		WithRegistration(reg),
	)
	return srv, mgr, &logBuf
}

func TestRegister_DisabledByDefaultReturns404(t *testing.T) {
	env := newTestEnv(t, 48*time.Hour)

	for _, method := range []string{http.MethodGet, http.MethodHead, http.MethodPost} {
		rec := env.do(t, method, "/register", "", "")
		if rec.Code != http.StatusNotFound {
			t.Fatalf("%s /register when disabled = %d, want 404", method, rec.Code)
		}
	}
}

func TestRegister_MultiUserGetRendersFormWithoutCreatingUsers(t *testing.T) {
	srv, mgr, _ := newMultiUserRegisterTestServer(t, true, 100, RegistrationConfig{
		Enabled:    true,
		PublicURL:  "https://pins.example.com",
		ServerName: "Home Pin Server",
	})

	req := httptest.NewRequest(http.MethodGet, "/register", nil)
	rec := httptest.NewRecorder()
	srv.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("GET /register = %d, want 200", rec.Code)
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
	if csp := rec.Header().Get("Content-Security-Policy"); !strings.Contains(csp, "form-action 'self'") {
		t.Errorf("CSP = %q, want form-action 'self'", csp)
	}
	body := rec.Body.String()
	if !strings.Contains(body, `method="POST" action="/register"`) {
		t.Errorf("expected registration POST form in GET /register body")
	}
	if !strings.Contains(body, "https://pins.example.com/&lt;random-id&gt;/pins.json") {
		t.Errorf("expected multi-user feed URL preview in form page")
	}
	if len(mgr.ListUsers()) != 0 {
		t.Fatalf("GET /register must not create users; got %d", len(mgr.ListUsers()))
	}

	// HEAD /register returns headers without body and creates no users.
	headReq := httptest.NewRequest(http.MethodHead, "/register", nil)
	headRec := httptest.NewRecorder()
	srv.ServeHTTP(headRec, headReq)
	if headRec.Code != http.StatusOK || headRec.Body.Len() != 0 {
		t.Fatalf("HEAD /register status=%d bodyLen=%d", headRec.Code, headRec.Body.Len())
	}
	if len(mgr.ListUsers()) != 0 {
		t.Fatalf("HEAD /register must not create users; got %d", len(mgr.ListUsers()))
	}
}

func TestRegister_MultiUserPostFormCreatesRandomUserAndServesQR(t *testing.T) {
	srv, mgr, logs := newMultiUserRegisterTestServer(t, true, 100, RegistrationConfig{
		Enabled:    true,
		PublicURL:  "https://pins.example.com/pins.json", // verify /pins.json suffix is stripped cleanly
		ServerName: "Home Pin Server",
	})

	form := url.Values{"name": {"Alice's Pebble"}}.Encode()
	req := httptest.NewRequest(http.MethodPost, "/register", strings.NewReader(form))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	rec := httptest.NewRecorder()
	srv.ServeHTTP(rec, req)

	if rec.Code != http.StatusCreated {
		t.Fatalf("POST /register = %d, want 201 (body=%s)", rec.Code, rec.Body.String())
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

	users := mgr.ListUsers()
	if len(users) != 1 {
		t.Fatalf("expected 1 registered user, got %d", len(users))
	}
	userID := users[0].ID
	if len(userID) != 32 {
		t.Fatalf("expected 32-char random user ID, got %q", userID)
	}

	body := rec.Body.String()
	expectedFeedURL := "https://pins.example.com/" + userID + "/pins.json"
	expectedAPIURL := "https://pins.example.com/api/v1/users/" + userID + "/pins"
	if !strings.Contains(body, "<svg ") || !strings.Contains(body, "</svg>") {
		t.Fatalf("expected inline SVG QR code in registration confirmation page")
	}
	if !strings.Contains(body, expectedFeedURL) {
		t.Errorf("expected feed URL %q in page", expectedFeedURL)
	}
	if !strings.Contains(body, expectedAPIURL) {
		t.Errorf("expected pins API URL %q in page", expectedAPIURL)
	}
	if !strings.Contains(body, "Read-Only Token (Per-User)") {
		t.Errorf("expected per-user read-only token badge")
	}

	// Verify pairing href points to /<userId>/pins.json with a working per-user read token.
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
	readToken := q.Get("token")
	if q.Get("v") != "1" || q.Get("auth") != "bearer" || readToken == "" ||
		q.Get("url") != expectedFeedURL || q.Get("name") != "Alice's Pebble" ||
		q.Get("allowHttp") != "" {
		t.Fatalf("unexpected pairing query params: %v", q)
	}

	// Verify the minted read token works on /<userId>/pins.json.
	feedReq := httptest.NewRequest(http.MethodGet, "/"+userID+"/pins.json", nil)
	feedReq.Header.Set("Authorization", "Bearer "+readToken)
	feedRec := httptest.NewRecorder()
	srv.ServeHTTP(feedRec, feedReq)
	if feedRec.Code != http.StatusOK {
		t.Fatalf("GET /%s/pins.json with minted read token = %d, want 200", userID, feedRec.Code)
	}

	// Verify neither the per-user read token nor global tokens leaked into logs.
	if strings.Contains(logs.String(), readToken) || strings.Contains(logs.String(), testReadToken) || strings.Contains(logs.String(), testWriteToken) {
		t.Fatalf("secret token leaked into logs: %s", logs.String())
	}
}

func TestRegister_MultiUserPostJSONAndLimit(t *testing.T) {
	srv, _, logs := newMultiUserRegisterTestServer(t, true, 2, RegistrationConfig{
		Enabled:    true,
		PublicURL:  "https://pins.example.com",
		ServerName: "Default Name",
	})

	req1 := httptest.NewRequest(http.MethodPost, "/register", strings.NewReader(`{"name":"Phone 1"}`))
	req1.Header.Set("Content-Type", "application/json")
	rec1 := httptest.NewRecorder()
	srv.ServeHTTP(rec1, req1)
	if rec1.Code != http.StatusCreated {
		t.Fatalf("POST /register JSON #1 = %d (%s), want 201", rec1.Code, rec1.Body.String())
	}
	var user1 userCreatedResponse
	if err := json.Unmarshal(rec1.Body.Bytes(), &user1); err != nil {
		t.Fatalf("decode user1: %v", err)
	}
	if user1.ID == "" || user1.ReadToken == "" || user1.WriteToken == "" {
		t.Fatalf("missing fields in user1: %+v", user1)
	}
	if user1.FeedURL != "https://pins.example.com/"+user1.ID+"/pins.json" {
		t.Errorf("user1 FeedURL = %q", user1.FeedURL)
	}

	// Empty body with Accept: application/json creates a second user using ServerName fallback in pairing URI.
	req2 := httptest.NewRequest(http.MethodPost, "/register", nil)
	req2.Header.Set("Accept", "application/json")
	rec2 := httptest.NewRecorder()
	srv.ServeHTTP(rec2, req2)
	if rec2.Code != http.StatusCreated {
		t.Fatalf("POST /register JSON #2 = %d (%s), want 201", rec2.Code, rec2.Body.String())
	}
	var user2 userCreatedResponse
	if err := json.Unmarshal(rec2.Body.Bytes(), &user2); err != nil {
		t.Fatalf("decode user2: %v", err)
	}
	if user2.ID == user1.ID || user2.ReadToken == user1.ReadToken {
		t.Fatalf("expected distinct IDs and tokens across registrations")
	}
	if !strings.Contains(user2.PairingURI, "name=Default%20Name") {
		t.Errorf("expected ServerName fallback in pairingUri, got %q", user2.PairingURI)
	}

	// Third registration hits maxUsers=2 -> 403 Forbidden.
	req3 := httptest.NewRequest(http.MethodPost, "/register", strings.NewReader(`{"name":"Phone 3"}`))
	req3.Header.Set("Content-Type", "application/json")
	rec3 := httptest.NewRecorder()
	srv.ServeHTTP(rec3, req3)
	if rec3.Code != http.StatusForbidden {
		t.Fatalf("POST /register over limit = %d, want 403", rec3.Code)
	}

	// Ensure minted tokens never appear in server logs.
	logStr := logs.String()
	for _, secret := range []string{user1.ReadToken, user1.WriteToken, user2.ReadToken, user2.WriteToken} {
		if strings.Contains(logStr, secret) {
			t.Fatalf("minted token leaked into logs")
		}
	}
}

func TestRegister_LegacyFallbackEnabledServesQRPageAndNeverLogsToken(t *testing.T) {
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
	body := rec.Body.String()
	if !strings.Contains(body, "<svg ") || !strings.Contains(body, "https://pins.example.com/pins.json") {
		t.Fatalf("expected legacy QR SVG and /pins.json URL")
	}
	if strings.Contains(logs.String(), testReadToken) || strings.Contains(logs.String(), testWriteToken) {
		t.Fatalf("token leaked into logs: %s", logs.String())
	}
}

func TestRegister_InfersURLFromProxyHeadersAndWarnsOnLocalhost(t *testing.T) {
	srv, _, _ := newMultiUserRegisterTestServer(t, true, 100, RegistrationConfig{
		Enabled: true,
	})

	// Case 1: Reverse proxy HTTPS headers.
	req := httptest.NewRequest(http.MethodPost, "http://127.0.0.1:8080/register", nil)
	req.Header.Set("X-Forwarded-Proto", "https")
	req.Header.Set("X-Forwarded-Host", "pins.myhome.example")
	rec := httptest.NewRecorder()
	srv.ServeHTTP(rec, req)

	body := rec.Body.String()
	if rec.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201", rec.Code)
	}
	if !strings.Contains(body, "https://pins.myhome.example/") || !strings.Contains(body, "/pins.json") {
		t.Errorf("expected inferred HTTPS proxy per-user URL in page")
	}
	if strings.Contains(body, "Localhost address detected") {
		t.Errorf("did not expect localhost warning for proxy host")
	}

	// Case 2: Direct localhost HTTP request -> localhost + HTTP warnings + allowHttp=1 in URI.
	localReq := httptest.NewRequest(http.MethodPost, "http://127.0.0.1:8080/register", nil)
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
	srv, _, _ := newMultiUserRegisterTestServer(t, false, 100, RegistrationConfig{
		Enabled: true,
	})

	req := httptest.NewRequest(http.MethodPost, "http://10.0.0.5:8080/register", nil)
	req.Header.Set("X-Forwarded-Proto", "https")
	req.Header.Set("X-Forwarded-Host", "spoofed.attacker.example")
	rec := httptest.NewRecorder()
	srv.ServeHTTP(rec, req)

	body := rec.Body.String()
	if strings.Contains(body, "spoofed.attacker.example") {
		t.Fatalf("must not trust X-Forwarded-Host when TrustProxy is false")
	}
	if !strings.Contains(body, "http://10.0.0.5:8080/") || !strings.Contains(body, "/pins.json") {
		t.Fatalf("expected direct request host URL in page")
	}
}
