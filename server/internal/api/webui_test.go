package api

import (
	"bytes"
	"encoding/json"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
	"time"

	"github.com/gabrielhidasy/PebblePinManager/server/internal/model"
	"github.com/gabrielhidasy/PebblePinManager/server/internal/store"
)

type webUITestEnv struct {
	server *Server
	users  *store.UserManager
	logs   *bytes.Buffer
	now    *time.Time
}

func newWebUITestEnv(t *testing.T, trustProxy bool, webUI WebUIConfig, reg RegistrationConfig) *webUITestEnv {
	t.Helper()
	dir := t.TempDir()
	now := time.Date(2026, 11, 26, 12, 0, 0, 0, time.UTC)
	clock := func() time.Time { return now }

	st, err := store.New(filepath.Join(dir, "pins.json"), 48*time.Hour, store.WithClock(clock))
	if err != nil {
		t.Fatalf("store.New: %v", err)
	}
	mgr, err := store.NewUserManager(dir, 48*time.Hour, store.WithUserClock(clock), store.WithMaxUsers(100))
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
		WithPublicURL("https://pins.example.com"),
		WithServerName("Home Pin Server"),
		WithRegistration(reg),
		WithWebUI(webUI),
	)
	return &webUITestEnv{
		server: srv,
		users:  mgr,
		logs:   &logBuf,
		now:    &now,
	}
}

var csrfMetaRegex = regexp.MustCompile(`<meta name="csrf-token" content="([0-9a-f]{64})">`)

func (e *webUITestEnv) fetchCSRF(t *testing.T) (*http.Cookie, string) {
	t.Helper()
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	rec := httptest.NewRecorder()
	e.server.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("GET / = %d, want 200", rec.Code)
	}
	var csrfCookie *http.Cookie
	for _, c := range rec.Result().Cookies() {
		if c.Name == uiCSRFCookieName {
			csrfCookie = c
		}
	}
	if csrfCookie == nil {
		t.Fatalf("missing %s cookie on GET /", uiCSRFCookieName)
	}
	matches := csrfMetaRegex.FindStringSubmatch(rec.Body.String())
	if len(matches) != 2 {
		t.Fatalf("missing csrf-token meta tag in GET / body")
	}
	if matches[1] != csrfCookie.Value {
		t.Fatalf("meta csrf token %q != cookie %q", matches[1], csrfCookie.Value)
	}
	return csrfCookie, matches[1]
}

func TestWebUI_DisabledReturns404(t *testing.T) {
	env := newWebUITestEnv(t, true, WebUIConfig{Enabled: false}, RegistrationConfig{})

	for _, path := range []string{"/", "/ui/style.css", "/ui/app.js", "/ui/api/session"} {
		req := httptest.NewRequest(http.MethodGet, path, nil)
		rec := httptest.NewRecorder()
		env.server.ServeHTTP(rec, req)
		if rec.Code != http.StatusNotFound {
			t.Fatalf("GET %s when disabled = %d, want 404", path, rec.Code)
		}
	}
}

func TestWebUI_AssetsSecurityHeadersAndExactRootMatch(t *testing.T) {
	env := newWebUITestEnv(t, true, WebUIConfig{Enabled: true, AuthProxyHeader: "Remote-User"}, RegistrationConfig{})

	req := httptest.NewRequest(http.MethodGet, "/", nil)
	rec := httptest.NewRecorder()
	env.server.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("GET / = %d, want 200", rec.Code)
	}
	if ct := rec.Header().Get("Content-Type"); !strings.HasPrefix(ct, "text/html") {
		t.Errorf("Content-Type = %q", ct)
	}
	csp := rec.Header().Get("Content-Security-Policy")
	if !strings.Contains(csp, "script-src 'self'") || strings.Contains(csp, "unsafe-inline") {
		t.Errorf("expected strict CSP without unsafe-inline, got %q", csp)
	}
	if !strings.Contains(rec.Header().Get("Cache-Control"), "no-store") {
		t.Errorf("expected no-store Cache-Control, got %q", rec.Header().Get("Cache-Control"))
	}

	var csrfCookie *http.Cookie
	for _, c := range rec.Result().Cookies() {
		if c.Name == uiCSRFCookieName {
			csrfCookie = c
		}
	}
	if csrfCookie == nil || !csrfCookie.HttpOnly || !csrfCookie.Secure || csrfCookie.SameSite != http.SameSiteStrictMode {
		t.Fatalf("unexpected CSRF cookie attributes: %+v", csrfCookie)
	}

	// Static CSS & JS assets.
	cssRec := httptest.NewRecorder()
	env.server.ServeHTTP(cssRec, httptest.NewRequest(http.MethodGet, "/ui/style.css", nil))
	if cssRec.Code != http.StatusOK || !strings.HasPrefix(cssRec.Header().Get("Content-Type"), "text/css") {
		t.Fatalf("GET /ui/style.css = %d (%q)", cssRec.Code, cssRec.Header().Get("Content-Type"))
	}

	jsRec := httptest.NewRecorder()
	env.server.ServeHTTP(jsRec, httptest.NewRequest(http.MethodGet, "/ui/app.js", nil))
	if jsRec.Code != http.StatusOK || !strings.HasPrefix(jsRec.Header().Get("Content-Type"), "application/javascript") {
		t.Fatalf("GET /ui/app.js = %d (%q)", jsRec.Code, jsRec.Header().Get("Content-Type"))
	}
	if strings.Contains(jsRec.Body.String(), "innerHTML") {
		t.Fatalf("app.js must not use innerHTML")
	}
	for _, needle := range []string{`id="icon-picker"`, `id="qr-toggle-btn"`, `Click to view QR code`} {
		if !strings.Contains(rec.Body.String(), needle) {
			t.Errorf("GET / missing expected UI element %q", needle)
		}
	}
	for _, needle := range []string{`QR_VISIBLE_SECONDS = 30`, `/ui/api/pairing`, `PIN_ICONS`, `createIconSvg`} {
		if !strings.Contains(jsRec.Body.String(), needle) {
			t.Errorf("GET /ui/app.js missing expected feature %q", needle)
		}
	}

	// Exact root match /{$}: unknown paths still return 404.
	notFoundRec := httptest.NewRecorder()
	env.server.ServeHTTP(notFoundRec, httptest.NewRequest(http.MethodGet, "/does-not-exist", nil))
	if notFoundRec.Code != http.StatusNotFound {
		t.Fatalf("GET /does-not-exist = %d, want 404", notFoundRec.Code)
	}
}

func TestWebUI_AutheliaProxyMultiUserProvisioningPinsAndTokenRetrieval(t *testing.T) {
	env := newWebUITestEnv(
		t,
		true,
		WebUIConfig{
			Enabled:             true,
			AuthProxyHeader:     "Remote-User",
			AuthProxyNameHeader: "Remote-Name",
		},
		RegistrationConfig{Enabled: true},
	)

	csrfCookie, csrfTok := env.fetchCSRF(t)

	// 1. Alice visits /ui/api/session via Authelia Remote-User header -> auto-provisioned.
	sessReq := httptest.NewRequest(http.MethodGet, "/ui/api/session", nil)
	sessReq.Header.Set("Remote-User", "alice")
	sessReq.Header.Set("Remote-Name", "Alice Pebble")
	sessRec := httptest.NewRecorder()
	env.server.ServeHTTP(sessRec, sessReq)
	if sessRec.Code != http.StatusOK {
		t.Fatalf("alice session = %d (%s)", sessRec.Code, sessRec.Body.String())
	}

	var aliceSess uiSessionResponse
	if err := json.Unmarshal(sessRec.Body.Bytes(), &aliceSess); err != nil {
		t.Fatalf("decode alice session: %v", err)
	}
	if !aliceSess.Authenticated || aliceSess.AuthMode != "proxy" || aliceSess.ProxySubject != "alice" {
		t.Fatalf("unexpected alice session: %+v", aliceSess)
	}
	if aliceSess.User == nil || len(aliceSess.User.ID) != 32 || aliceSess.User.Name != "Alice Pebble" {
		t.Fatalf("unexpected alice user: %+v", aliceSess.User)
	}
	if !aliceSess.TokensAvailable {
		t.Fatalf("expected tokensAvailable=true for newly provisioned proxy user")
	}
	// Session payload must NOT expose pairingUri, qrDataUri, or raw tokens on initial load.
	sessRaw := sessRec.Body.String()
	for _, forbidden := range []string{"pairingUri", "qrDataUri", "pebblepin://", "data:image/svg+xml"} {
		if strings.Contains(sessRaw, forbidden) {
			t.Fatalf("GET /ui/api/session leaked %q: %s", forbidden, sessRaw)
		}
	}

	// 1b. Pairing endpoint enforces CSRF and returns QR + pairing URI on demand without writeToken.
	noCSRFPairingReq := httptest.NewRequest(http.MethodPost, "/ui/api/pairing", nil)
	noCSRFPairingReq.Header.Set("Remote-User", "alice")
	noCSRFPairingRec := httptest.NewRecorder()
	env.server.ServeHTTP(noCSRFPairingRec, noCSRFPairingReq)
	if noCSRFPairingRec.Code != http.StatusForbidden {
		t.Fatalf("POST /ui/api/pairing without CSRF = %d, want 403", noCSRFPairingRec.Code)
	}

	pairingReq := httptest.NewRequest(http.MethodPost, "/ui/api/pairing", nil)
	pairingReq.Header.Set("Remote-User", "alice")
	pairingReq.Header.Set(uiCSRFHeaderName, csrfTok)
	pairingReq.AddCookie(csrfCookie)
	pairingRec := httptest.NewRecorder()
	env.server.ServeHTTP(pairingRec, pairingReq)
	if pairingRec.Code != http.StatusOK {
		t.Fatalf("POST /ui/api/pairing = %d (%s)", pairingRec.Code, pairingRec.Body.String())
	}
	if strings.Contains(pairingRec.Body.String(), "writeToken") {
		t.Fatalf("POST /ui/api/pairing must not expose writeToken: %s", pairingRec.Body.String())
	}
	var alicePairing uiPairingResponse
	if err := json.Unmarshal(pairingRec.Body.Bytes(), &alicePairing); err != nil {
		t.Fatalf("decode alice pairing: %v", err)
	}
	if !alicePairing.Available || !strings.HasPrefix(alicePairing.QRDataURI, "data:image/svg+xml;base64,") {
		t.Fatalf("expected QR data URI from POST /ui/api/pairing, got %+v", alicePairing)
	}
	if !strings.Contains(alicePairing.PairingURI, "https%3A%2F%2Fpins.example.com%2F"+aliceSess.User.ID+"%2Fpins.json") {
		t.Fatalf("unexpected pairingUri: %q", alicePairing.PairingURI)
	}

	// 2. Alice visits /register behind Authelia -> redirects to /#pair without creating a second user.
	regReq := httptest.NewRequest(http.MethodGet, "/register", nil)
	regReq.Header.Set("Remote-User", "alice")
	regRec := httptest.NewRecorder()
	env.server.ServeHTTP(regRec, regReq)
	if regRec.Code != http.StatusSeeOther || regRec.Header().Get("Location") != "/#pair" {
		t.Fatalf("GET /register with Remote-User = %d (Location=%q), want 303 -> /#pair", regRec.Code, regRec.Header().Get("Location"))
	}
	if len(env.users.ListUsers()) != 1 {
		t.Fatalf("expected 1 user after /register redirect, got %d", len(env.users.ListUsers()))
	}

	// 3. Alice retrieves her tokens from the authenticated Authelia page.
	revealReq := httptest.NewRequest(http.MethodPost, "/ui/api/tokens/reveal", nil)
	revealReq.Header.Set("Remote-User", "alice")
	revealReq.Header.Set(uiCSRFHeaderName, csrfTok)
	revealReq.AddCookie(csrfCookie)
	revealRec := httptest.NewRecorder()
	env.server.ServeHTTP(revealRec, revealReq)
	if revealRec.Code != http.StatusOK {
		t.Fatalf("reveal tokens = %d (%s)", revealRec.Code, revealRec.Body.String())
	}
	var aliceTokens uiTokensResponse
	if err := json.Unmarshal(revealRec.Body.Bytes(), &aliceTokens); err != nil {
		t.Fatalf("decode alice tokens: %v", err)
	}
	if !aliceTokens.Available || aliceTokens.ReadToken == "" || aliceTokens.WriteToken == "" {
		t.Fatalf("expected revealed tokens, got %+v", aliceTokens)
	}

	// 4. Alice adds a pin via the Web UI.
	createPinReq := httptest.NewRequest(http.MethodPost, "/ui/api/pins", strings.NewReader(`{
		"title": "Authelia UI Pin",
		"description": "Created in browser",
		"startTime": "2026-11-27T15:00:00Z",
		"durationMinutes": 30,
		"backgroundColor": "#00AAFF",
		"layoutType": "CALENDAR_PIN"
	}`))
	createPinReq.Header.Set("Content-Type", "application/json")
	createPinReq.Header.Set("Remote-User", "alice")
	createPinReq.Header.Set(uiCSRFHeaderName, csrfTok)
	createPinReq.AddCookie(csrfCookie)
	createPinRec := httptest.NewRecorder()
	env.server.ServeHTTP(createPinRec, createPinReq)
	if createPinRec.Code != http.StatusCreated {
		t.Fatalf("UI create pin = %d (%s)", createPinRec.Code, createPinRec.Body.String())
	}
	var createdPin model.Pin
	if err := json.Unmarshal(createPinRec.Body.Bytes(), &createdPin); err != nil {
		t.Fatal(err)
	}

	// 5. Script pushes a second pin using Alice's retrieved WriteToken via /api/v1/users/{id}/pins.
	scriptReq := httptest.NewRequest(http.MethodPut, "/api/v1/users/"+aliceSess.User.ID+"/pins/cron-pin", strings.NewReader(`{
		"title": "Script Pin",
		"startTime": "2026-11-27T16:00:00Z"
	}`))
	scriptReq.Header.Set("Content-Type", "application/json")
	scriptReq.Header.Set("Authorization", "Bearer "+aliceTokens.WriteToken)
	scriptRec := httptest.NewRecorder()
	env.server.ServeHTTP(scriptRec, scriptReq)
	if scriptRec.Code != http.StatusCreated {
		t.Fatalf("script PUT pin = %d (%s)", scriptRec.Code, scriptRec.Body.String())
	}

	// 6. Phone fetches Alice's isolated feed using her retrieved ReadToken -> sees both pins!
	feedReq := httptest.NewRequest(http.MethodGet, "/"+aliceSess.User.ID+"/pins.json", nil)
	feedReq.Header.Set("Authorization", "Bearer "+aliceTokens.ReadToken)
	feedRec := httptest.NewRecorder()
	env.server.ServeHTTP(feedRec, feedReq)
	if feedRec.Code != http.StatusOK {
		t.Fatalf("alice feed = %d (%s)", feedRec.Code, feedRec.Body.String())
	}
	var feed model.PinFeed
	if err := json.Unmarshal(feedRec.Body.Bytes(), &feed); err != nil {
		t.Fatal(err)
	}
	if len(feed.Pins) != 2 {
		t.Fatalf("expected 2 pins in Alice's feed, got %d", len(feed.Pins))
	}

	// 7. Alice updates and deletes a pin via Web UI.
	editReq := httptest.NewRequest(http.MethodPut, "/ui/api/pins/"+createdPin.ID, strings.NewReader(`{
		"title": "Updated UI Pin",
		"startTime": "2026-11-27T15:30:00Z"
	}`))
	editReq.Header.Set("Content-Type", "application/json")
	editReq.Header.Set("Remote-User", "alice")
	editReq.Header.Set(uiCSRFHeaderName, csrfTok)
	editReq.AddCookie(csrfCookie)
	editRec := httptest.NewRecorder()
	env.server.ServeHTTP(editRec, editReq)
	if editRec.Code != http.StatusOK {
		t.Fatalf("UI edit pin = %d (%s)", editRec.Code, editRec.Body.String())
	}

	delReq := httptest.NewRequest(http.MethodDelete, "/ui/api/pins/cron-pin", nil)
	delReq.Header.Set("Remote-User", "alice")
	delReq.Header.Set(uiCSRFHeaderName, csrfTok)
	delReq.AddCookie(csrfCookie)
	delRec := httptest.NewRecorder()
	env.server.ServeHTTP(delRec, delReq)
	if delRec.Code != http.StatusNoContent {
		t.Fatalf("UI delete pin = %d (%s)", delRec.Code, delRec.Body.String())
	}

	// 8. Bob visits via Authelia -> gets his own isolated user and sees 0 pins.
	bobPinsReq := httptest.NewRequest(http.MethodGet, "/ui/api/pins", nil)
	bobPinsReq.Header.Set("Remote-User", "bob")
	bobPinsRec := httptest.NewRecorder()
	env.server.ServeHTTP(bobPinsRec, bobPinsReq)
	if bobPinsRec.Code != http.StatusOK {
		t.Fatalf("bob pins = %d (%s)", bobPinsRec.Code, bobPinsRec.Body.String())
	}
	var bobFeed model.PinFeed
	if err := json.Unmarshal(bobPinsRec.Body.Bytes(), &bobFeed); err != nil {
		t.Fatal(err)
	}
	if len(bobFeed.Pins) != 0 {
		t.Fatalf("bob must not see Alice's pins, got %d", len(bobFeed.Pins))
	}

	// 9. Security: Remote-User header must NEVER grant access to /api/v1/* or /{userId}/pins.json.
	unauthFeedReq := httptest.NewRequest(http.MethodGet, "/"+aliceSess.User.ID+"/pins.json", nil)
	unauthFeedReq.Header.Set("Remote-User", "alice")
	unauthFeedRec := httptest.NewRecorder()
	env.server.ServeHTTP(unauthFeedRec, unauthFeedReq)
	if unauthFeedRec.Code != http.StatusUnauthorized {
		t.Fatalf("feed with Remote-User only = %d, want 401", unauthFeedRec.Code)
	}

	unauthAdminReq := httptest.NewRequest(http.MethodGet, "/api/v1/users", nil)
	unauthAdminReq.Header.Set("Remote-User", "alice")
	unauthAdminRec := httptest.NewRecorder()
	env.server.ServeHTTP(unauthAdminRec, unauthAdminReq)
	if unauthAdminRec.Code != http.StatusUnauthorized {
		t.Fatalf("admin API with Remote-User only = %d, want 401", unauthAdminRec.Code)
	}

	// 10. Logs must NEVER contain raw tokens or CSRF values.
	logs := env.logs.String()
	for _, secret := range []string{aliceTokens.ReadToken, aliceTokens.WriteToken, csrfTok} {
		if strings.Contains(logs, secret) {
			t.Fatalf("access logs leaked secret %q", secret)
		}
	}
}

func TestWebUI_ProxyHeaderIgnoredUnlessExplicitlyConfiguredAndTrusted(t *testing.T) {
	// Case A: trustProxy=false even when AuthProxyHeader is set.
	envNoTrust := newWebUITestEnv(t, false, WebUIConfig{Enabled: true, AuthProxyHeader: "Remote-User"}, RegistrationConfig{})
	req := httptest.NewRequest(http.MethodGet, "/ui/api/session", nil)
	req.Header.Set("Remote-User", "spoofed")
	rec := httptest.NewRecorder()
	envNoTrust.server.ServeHTTP(rec, req)
	var sessA uiSessionResponse
	if err := json.Unmarshal(rec.Body.Bytes(), &sessA); err != nil {
		t.Fatal(err)
	}
	if sessA.Authenticated {
		t.Fatalf("Remote-User must be ignored when trustProxy=false")
	}

	// Case B: trustProxy=true but AuthProxyHeader is empty.
	envNoHeader := newWebUITestEnv(t, true, WebUIConfig{Enabled: true, AuthProxyHeader: ""}, RegistrationConfig{})
	reqB := httptest.NewRequest(http.MethodGet, "/ui/api/session", nil)
	reqB.Header.Set("Remote-User", "spoofed")
	recB := httptest.NewRecorder()
	envNoHeader.server.ServeHTTP(recB, reqB)
	var sessB uiSessionResponse
	if err := json.Unmarshal(recB.Body.Bytes(), &sessB); err != nil {
		t.Fatal(err)
	}
	if sessB.Authenticated {
		t.Fatalf("Remote-User must be ignored when AuthProxyHeader is empty")
	}
}

func TestWebUI_StandaloneTokenLoginCSRFTokenRotationAndAdminLegacy(t *testing.T) {
	env := newWebUITestEnv(t, true, WebUIConfig{Enabled: true}, RegistrationConfig{Enabled: true})
	csrfCookie, csrfTok := env.fetchCSRF(t)

	// Create a user via UserManager.
	created, err := env.users.CreateUser("Standalone Alice")
	if err != nil {
		t.Fatal(err)
	}

	// CSRF is enforced on POST /ui/api/login.
	noCSRFRec := httptest.NewRecorder()
	noCSRFReq := httptest.NewRequest(http.MethodPost, "/ui/api/login", strings.NewReader(`{"token":"`+created.WriteToken+`"}`))
	noCSRFReq.Header.Set("Content-Type", "application/json")
	env.server.ServeHTTP(noCSRFRec, noCSRFReq)
	if noCSRFRec.Code != http.StatusForbidden {
		t.Fatalf("login without CSRF = %d, want 403", noCSRFRec.Code)
	}

	// Per-user read token cannot sign into Web UI.
	readLoginReq := httptest.NewRequest(http.MethodPost, "/ui/api/login", strings.NewReader(`{"token":"`+created.ReadToken+`"}`))
	readLoginReq.Header.Set("Content-Type", "application/json")
	readLoginReq.Header.Set(uiCSRFHeaderName, csrfTok)
	readLoginReq.AddCookie(csrfCookie)
	readLoginRec := httptest.NewRecorder()
	env.server.ServeHTTP(readLoginRec, readLoginReq)
	if readLoginRec.Code != http.StatusUnauthorized {
		t.Fatalf("login with user read token = %d, want 401", readLoginRec.Code)
	}

	// Global read token returns 403.
	globalReadReq := httptest.NewRequest(http.MethodPost, "/ui/api/login", strings.NewReader(`{"token":"`+testReadToken+`"}`))
	globalReadReq.Header.Set("Content-Type", "application/json")
	globalReadReq.Header.Set(uiCSRFHeaderName, csrfTok)
	globalReadReq.AddCookie(csrfCookie)
	globalReadRec := httptest.NewRecorder()
	env.server.ServeHTTP(globalReadRec, globalReadReq)
	if globalReadRec.Code != http.StatusForbidden {
		t.Fatalf("login with global read token = %d, want 403", globalReadRec.Code)
	}

	// Sign in twice (two browser sessions) using Alice's write token.
	loginUser := func() *http.Cookie {
		req := httptest.NewRequest(http.MethodPost, "/ui/api/login", strings.NewReader(`{"token":"`+created.WriteToken+`"}`))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set(uiCSRFHeaderName, csrfTok)
		req.AddCookie(csrfCookie)
		rec := httptest.NewRecorder()
		env.server.ServeHTTP(rec, req)
		if rec.Code != http.StatusOK {
			t.Fatalf("user login = %d (%s)", rec.Code, rec.Body.String())
		}
		for _, c := range rec.Result().Cookies() {
			if c.Name == uiSessionCookieName {
				if !c.HttpOnly || !c.Secure || c.SameSite != http.SameSiteStrictMode {
					t.Fatalf("unexpected session cookie attrs: %+v", c)
				}
				return c
			}
		}
		t.Fatalf("missing session cookie")
		return nil
	}

	sess1 := loginUser()
	sess2 := loginUser()

	// Update profile name via session 1 -> updates QR pairing URI returned on demand.
	profReq := httptest.NewRequest(http.MethodPut, "/ui/api/profile", strings.NewReader(`{"name":"Renamed Watch"}`))
	profReq.Header.Set("Content-Type", "application/json")
	profReq.Header.Set(uiCSRFHeaderName, csrfTok)
	profReq.AddCookie(csrfCookie)
	profReq.AddCookie(sess1)
	profRec := httptest.NewRecorder()
	env.server.ServeHTTP(profRec, profReq)
	if profRec.Code != http.StatusOK {
		t.Fatalf("update profile = %d (%s)", profRec.Code, profRec.Body.String())
	}
	var profResp uiSessionResponse
	if err := json.Unmarshal(profRec.Body.Bytes(), &profResp); err != nil {
		t.Fatal(err)
	}
	if profResp.User == nil || profResp.User.Name != "Renamed Watch" || !profResp.TokensAvailable {
		t.Fatalf("unexpected profile response: %+v", profResp)
	}

	renamedPairingReq := httptest.NewRequest(http.MethodPost, "/ui/api/pairing", nil)
	renamedPairingReq.Header.Set(uiCSRFHeaderName, csrfTok)
	renamedPairingReq.AddCookie(csrfCookie)
	renamedPairingReq.AddCookie(sess1)
	renamedPairingRec := httptest.NewRecorder()
	env.server.ServeHTTP(renamedPairingRec, renamedPairingReq)
	if renamedPairingRec.Code != http.StatusOK {
		t.Fatalf("pairing after rename = %d (%s)", renamedPairingRec.Code, renamedPairingRec.Body.String())
	}
	var renamedPairing uiPairingResponse
	if err := json.Unmarshal(renamedPairingRec.Body.Bytes(), &renamedPairing); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(renamedPairing.PairingURI, "name=Renamed%20Watch") {
		t.Fatalf("expected renamed pairingUri, got %+v", renamedPairing)
	}

	// Rotate write token in session 1 -> session 1 remains valid, session 2 is invalidated!
	rotReq := httptest.NewRequest(http.MethodPost, "/ui/api/tokens/rotate", strings.NewReader(`{"rotateRead":false,"rotateWrite":true}`))
	rotReq.Header.Set("Content-Type", "application/json")
	rotReq.Header.Set(uiCSRFHeaderName, csrfTok)
	rotReq.AddCookie(csrfCookie)
	rotReq.AddCookie(sess1)
	rotRec := httptest.NewRecorder()
	env.server.ServeHTTP(rotRec, rotReq)
	if rotRec.Code != http.StatusOK {
		t.Fatalf("rotate write token = %d (%s)", rotRec.Code, rotRec.Body.String())
	}
	var rotResp uiTokensResponse
	if err := json.Unmarshal(rotRec.Body.Bytes(), &rotResp); err != nil {
		t.Fatal(err)
	}
	if rotResp.WriteToken == "" || rotResp.WriteToken == created.WriteToken || rotResp.ReadToken != created.ReadToken {
		t.Fatalf("unexpected rotate response: %+v", rotResp)
	}

	checkSess1 := httptest.NewRequest(http.MethodGet, "/ui/api/pins", nil)
	checkSess1.AddCookie(sess1)
	recSess1 := httptest.NewRecorder()
	env.server.ServeHTTP(recSess1, checkSess1)
	if recSess1.Code != http.StatusOK {
		t.Fatalf("current session after write rotation = %d, want 200", recSess1.Code)
	}

	checkSess2 := httptest.NewRequest(http.MethodGet, "/ui/api/pins", nil)
	checkSess2.AddCookie(sess2)
	recSess2 := httptest.NewRecorder()
	env.server.ServeHTTP(recSess2, checkSess2)
	if recSess2.Code != http.StatusUnauthorized {
		t.Fatalf("other session after write rotation = %d, want 401", recSess2.Code)
	}

	// Deleting user via admin API invalidates remaining session 1.
	delUserReq := httptest.NewRequest(http.MethodDelete, "/api/v1/users/"+created.User.ID, nil)
	delUserReq.Header.Set("Authorization", "Bearer "+testWriteToken)
	delUserRec := httptest.NewRecorder()
	env.server.ServeHTTP(delUserRec, delUserReq)
	if delUserRec.Code != http.StatusNoContent {
		t.Fatalf("admin delete user = %d, want 204", delUserRec.Code)
	}

	afterDelReq := httptest.NewRequest(http.MethodGet, "/ui/api/pins", nil)
	afterDelReq.AddCookie(sess1)
	afterDelRec := httptest.NewRecorder()
	env.server.ServeHTTP(afterDelRec, afterDelReq)
	if afterDelRec.Code != http.StatusUnauthorized {
		t.Fatalf("session after user deletion = %d, want 401", afterDelRec.Code)
	}

	// Global admin write token signs into legacy single-user feed.
	adminLoginReq := httptest.NewRequest(http.MethodPost, "/ui/api/login", strings.NewReader(`{"token":"`+testWriteToken+`"}`))
	adminLoginReq.Header.Set("Content-Type", "application/json")
	adminLoginReq.Header.Set(uiCSRFHeaderName, csrfTok)
	adminLoginReq.AddCookie(csrfCookie)
	adminLoginRec := httptest.NewRecorder()
	env.server.ServeHTTP(adminLoginRec, adminLoginReq)
	if adminLoginRec.Code != http.StatusOK {
		t.Fatalf("admin login = %d (%s)", adminLoginRec.Code, adminLoginRec.Body.String())
	}
	var adminSessCookie *http.Cookie
	for _, c := range adminLoginRec.Result().Cookies() {
		if c.Name == uiSessionCookieName {
			adminSessCookie = c
		}
	}
	if adminSessCookie == nil {
		t.Fatalf("missing admin session cookie")
	}

	// Legacy admin session rejects /ui/api/pairing.
	legacyPairReq := httptest.NewRequest(http.MethodPost, "/ui/api/pairing", nil)
	legacyPairReq.Header.Set(uiCSRFHeaderName, csrfTok)
	legacyPairReq.AddCookie(csrfCookie)
	legacyPairReq.AddCookie(adminSessCookie)
	legacyPairRec := httptest.NewRecorder()
	env.server.ServeHTTP(legacyPairRec, legacyPairReq)
	if legacyPairRec.Code != http.StatusBadRequest {
		t.Fatalf("legacy /ui/api/pairing = %d, want 400", legacyPairRec.Code)
	}

	legacyPinReq := httptest.NewRequest(http.MethodPost, "/ui/api/pins", strings.NewReader(`{
		"title": "Legacy Feed Pin",
		"startTime": "2026-11-27T17:00:00Z"
	}`))
	legacyPinReq.Header.Set("Content-Type", "application/json")
	legacyPinReq.Header.Set(uiCSRFHeaderName, csrfTok)
	legacyPinReq.AddCookie(csrfCookie)
	legacyPinReq.AddCookie(adminSessCookie)
	legacyPinRec := httptest.NewRecorder()
	env.server.ServeHTTP(legacyPinRec, legacyPinReq)
	if legacyPinRec.Code != http.StatusCreated {
		t.Fatalf("legacy UI pin create = %d (%s)", legacyPinRec.Code, legacyPinRec.Body.String())
	}

	// Legacy /pins.json feed now serves that pin.
	legacyFeedReq := httptest.NewRequest(http.MethodGet, "/pins.json", nil)
	legacyFeedReq.Header.Set("Authorization", "Bearer "+testReadToken)
	legacyFeedRec := httptest.NewRecorder()
	env.server.ServeHTTP(legacyFeedRec, legacyFeedReq)
	if legacyFeedRec.Code != http.StatusOK || !strings.Contains(legacyFeedRec.Body.String(), "Legacy Feed Pin") {
		t.Fatalf("legacy feed missing pin: %d %s", legacyFeedRec.Code, legacyFeedRec.Body.String())
	}

	// Logout clears session.
	logoutReq := httptest.NewRequest(http.MethodPost, "/ui/api/logout", nil)
	logoutReq.Header.Set(uiCSRFHeaderName, csrfTok)
	logoutReq.AddCookie(csrfCookie)
	logoutReq.AddCookie(adminSessCookie)
	logoutRec := httptest.NewRecorder()
	env.server.ServeHTTP(logoutRec, logoutReq)
	if logoutRec.Code != http.StatusNoContent {
		t.Fatalf("logout = %d, want 204", logoutRec.Code)
	}

	afterLogoutReq := httptest.NewRequest(http.MethodGet, "/ui/api/pins", nil)
	afterLogoutReq.AddCookie(adminSessCookie)
	afterLogoutRec := httptest.NewRecorder()
	env.server.ServeHTTP(afterLogoutRec, afterLogoutReq)
	if afterLogoutRec.Code != http.StatusUnauthorized {
		t.Fatalf("after logout = %d, want 401", afterLogoutRec.Code)
	}
}
