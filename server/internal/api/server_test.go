package api

import (
	"bytes"
	"encoding/json"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/gabrielhidasy/PebblePinManager/server/internal/model"
	"github.com/gabrielhidasy/PebblePinManager/server/internal/store"
)

const (
	testWriteToken = "write-secret-token-1234567890"
	testReadToken  = "read-secret-token-0987654321"
)

type testEnv struct {
	server   *Server
	dataFile string
	logs     *bytes.Buffer
	now      *time.Time
}

func newTestEnv(t *testing.T, retention time.Duration) *testEnv {
	t.Helper()
	dataFile := filepath.Join(t.TempDir(), "pins.json")
	now := time.Date(2026, 11, 25, 12, 0, 0, 0, time.UTC)
	clock := func() time.Time { return now }

	st, err := store.New(dataFile, retention, store.WithClock(clock))
	if err != nil {
		t.Fatalf("store.New: %v", err)
	}

	var logBuf bytes.Buffer
	logger := slog.New(slog.NewJSONHandler(&logBuf, nil))
	auth := NewAuthenticator(testWriteToken, testReadToken)
	srv := NewServer(st, auth, WithLogger(logger), WithServerClock(clock))

	return &testEnv{
		server:   srv,
		dataFile: dataFile,
		logs:     &logBuf,
		now:      &now,
	}
}

func (e *testEnv) do(t *testing.T, method, path, token, body string) *httptest.ResponseRecorder {
	t.Helper()
	var bodyReader *bytes.Reader
	if body != "" {
		bodyReader = bytes.NewReader([]byte(body))
	} else {
		bodyReader = bytes.NewReader(nil)
	}
	req := httptest.NewRequest(method, path, bodyReader)
	if body != "" {
		req.Header.Set("Content-Type", "application/json")
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	rec := httptest.NewRecorder()
	e.server.ServeHTTP(rec, req)
	return rec
}

func TestHealthz_Unauthenticated(t *testing.T) {
	env := newTestEnv(t, 48*time.Hour)

	rec := env.do(t, http.MethodGet, "/healthz", "", "")
	if rec.Code != http.StatusOK {
		t.Fatalf("GET /healthz status = %d, want 200", rec.Code)
	}
	if !strings.Contains(rec.Body.String(), `"status":"ok"`) {
		t.Fatalf("unexpected body: %s", rec.Body.String())
	}

	headRec := env.do(t, http.MethodHead, "/healthz", "", "")
	if headRec.Code != http.StatusOK || headRec.Body.Len() != 0 {
		t.Fatalf("HEAD /healthz status=%d bodyLen=%d", headRec.Code, headRec.Body.Len())
	}
}

func TestAuth_UnauthorizedForbiddenAndNoTokenLogging(t *testing.T) {
	env := newTestEnv(t, 48*time.Hour)

	// Missing token -> 401 on all protected endpoints.
	for _, tc := range []struct {
		method string
		path   string
	}{
		{http.MethodGet, "/pins.json"},
		{http.MethodGet, "/api/v1/pins.json"},
		{http.MethodGet, "/api/v1/pins"},
		{http.MethodGet, "/api/v1/pins/pin-1"},
		{http.MethodPost, "/api/v1/pins"},
		{http.MethodPut, "/api/v1/pins/pin-1"},
		{http.MethodDelete, "/api/v1/pins/pin-1"},
	} {
		rec := env.do(t, tc.method, tc.path, "", "")
		if rec.Code != http.StatusUnauthorized {
			t.Errorf("%s %s without token = %d, want 401", tc.method, tc.path, rec.Code)
		}
		if rec.Header().Get("WWW-Authenticate") == "" {
			t.Errorf("%s %s missing WWW-Authenticate header", tc.method, tc.path)
		}
	}

	// Wrong token -> 401.
	rec := env.do(t, http.MethodGet, "/pins.json", "wrong-token", "")
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("wrong token status = %d, want 401", rec.Code)
	}

	// Malformed Authorization header must not fall back to X-API-Key.
	req := httptest.NewRequest(http.MethodGet, "/pins.json", nil)
	req.Header.Set("Authorization", "Basic dXNlcjpwYXNz")
	req.Header.Set("X-API-Key", testReadToken)
	malformedRec := httptest.NewRecorder()
	env.server.ServeHTTP(malformedRec, req)
	if malformedRec.Code != http.StatusUnauthorized {
		t.Fatalf("malformed auth header status = %d, want 401", malformedRec.Code)
	}

	// Valid X-API-Key read token works for reads.
	req = httptest.NewRequest(http.MethodGet, "/pins.json", nil)
	req.Header.Set("X-API-Key", testReadToken)
	apiKeyRec := httptest.NewRecorder()
	env.server.ServeHTTP(apiKeyRec, req)
	if apiKeyRec.Code != http.StatusOK {
		t.Fatalf("X-API-Key read status = %d, want 200", apiKeyRec.Code)
	}

	// Read-only token is forbidden on write methods (403).
	validPayload := `{"title":"Standup","startTime":"2026-11-26T14:00:00Z"}`
	for _, tc := range []struct {
		method string
		path   string
		body   string
	}{
		{http.MethodPost, "/api/v1/pins", validPayload},
		{http.MethodPut, "/api/v1/pins/pin-1", validPayload},
		{http.MethodDelete, "/api/v1/pins/pin-1", ""},
	} {
		rec := env.do(t, tc.method, tc.path, testReadToken, tc.body)
		if rec.Code != http.StatusForbidden {
			t.Errorf("%s %s with read token = %d, want 403", tc.method, tc.path, rec.Code)
		}
	}

	// Verify write token works and neither secret ever appears in logs.
	rec = env.do(t, http.MethodPost, "/api/v1/pins", testWriteToken, validPayload)
	if rec.Code != http.StatusCreated {
		t.Fatalf("POST with write token = %d (%s), want 201", rec.Code, rec.Body.String())
	}

	logOutput := env.logs.String()
	if strings.Contains(logOutput, testWriteToken) || strings.Contains(logOutput, testReadToken) {
		t.Fatalf("raw token leaked into server logs: %s", logOutput)
	}
}

func TestCRUDLifecycleAndValidation(t *testing.T) {
	env := newTestEnv(t, 48*time.Hour)

	// 1. Create with auto-generated ID.
	rec := env.do(t, http.MethodPost, "/api/v1/pins", testWriteToken, `{
		"title": "Team Standup",
		"description": "Daily sync in Room B",
		"startTime": "2026-11-26T14:00:00Z",
		"durationMinutes": 30,
		"iconUri": "system://images/TIMELINE_CALENDAR",
		"backgroundColor": "#00aaff",
		"layoutType": "CALENDAR_PIN"
	}`)
	if rec.Code != http.StatusCreated {
		t.Fatalf("POST create status = %d body = %s", rec.Code, rec.Body.String())
	}
	var created model.Pin
	if err := json.Unmarshal(rec.Body.Bytes(), &created); err != nil {
		t.Fatalf("decode created pin: %v", err)
	}
	if !strings.HasPrefix(created.ID, "ppm-") {
		t.Errorf("expected generated ppm-* ID, got %q", created.ID)
	}
	if rec.Header().Get("Location") != "/api/v1/pins/"+created.ID {
		t.Errorf("Location header = %q", rec.Header().Get("Location"))
	}
	if created.BackgroundColor == nil || *created.BackgroundColor != "#00AAFF" {
		t.Errorf("expected normalized #00AAFF, got %v", created.BackgroundColor)
	}

	// 2. Duplicate POST with explicit existing ID -> 409 Conflict.
	dupRec := env.do(t, http.MethodPost, "/api/v1/pins", testWriteToken, `{
		"id": "`+created.ID+`",
		"title": "Duplicate",
		"startTime": "2026-11-26T14:00:00Z"
	}`)
	if dupRec.Code != http.StatusConflict {
		t.Fatalf("duplicate POST status = %d, want 409", dupRec.Code)
	}

	// 3. Upsert new pin via PUT -> 201 Created.
	putCreate := env.do(t, http.MethodPut, "/api/v1/pins/ext-deploy-1", testWriteToken, `{
		"title": "Deploy v1.2",
		"startTime": "2026-11-26T18:00:00Z"
	}`)
	if putCreate.Code != http.StatusCreated {
		t.Fatalf("PUT create status = %d body = %s", putCreate.Code, putCreate.Body.String())
	}

	// 4. Upsert existing pin via PUT -> 200 OK.
	putUpdate := env.do(t, http.MethodPut, "/api/v1/pins/ext-deploy-1", testWriteToken, `{
		"id": "ext-deploy-1",
		"title": "Deploy v1.3",
		"startTime": "2026-11-26T19:00:00Z",
		"layoutType": "CALENDAR_PIN"
	}`)
	if putUpdate.Code != http.StatusOK {
		t.Fatalf("PUT update status = %d body = %s", putUpdate.Code, putUpdate.Body.String())
	}

	// 4b. Unsupported top-level pin layouts (rejected by PebbleOS pin_db) -> 400 Bad Request.
	for _, unsupportedLayout := range []string{"GENERIC_REMINDER", "GENERIC_NOTIFICATION"} {
		badLayout := env.do(t, http.MethodPost, "/api/v1/pins", testWriteToken, `{
			"title": "Unsupported layout",
			"startTime": "2026-11-26T19:00:00Z",
			"layoutType": "`+unsupportedLayout+`"
		}`)
		if badLayout.Code != http.StatusBadRequest {
			t.Fatalf("POST with layoutType=%s status = %d, want 400", unsupportedLayout, badLayout.Code)
		}
	}

	// 5. PUT with mismatched body ID -> 400 Bad Request.
	putMismatch := env.do(t, http.MethodPut, "/api/v1/pins/ext-deploy-1", testWriteToken, `{
		"id": "other-id",
		"title": "Mismatch",
		"startTime": "2026-11-26T19:00:00Z"
	}`)
	if putMismatch.Code != http.StatusBadRequest {
		t.Fatalf("PUT mismatch status = %d, want 400", putMismatch.Code)
	}

	// 6. Get single pin with read token.
	getRec := env.do(t, http.MethodGet, "/api/v1/pins/ext-deploy-1", testReadToken, "")
	if getRec.Code != http.StatusOK {
		t.Fatalf("GET single status = %d, want 200", getRec.Code)
	}
	var fetched model.Pin
	if err := json.Unmarshal(getRec.Body.Bytes(), &fetched); err != nil {
		t.Fatal(err)
	}
	if fetched.Title != "Deploy v1.3" || fetched.LayoutType != "CALENDAR_PIN" {
		t.Errorf("unexpected fetched pin: %+v", fetched)
	}

	// 7. List pins.
	listRec := env.do(t, http.MethodGet, "/api/v1/pins", testReadToken, "")
	if listRec.Code != http.StatusOK {
		t.Fatalf("GET list status = %d", listRec.Code)
	}
	var feed model.PinFeed
	if err := json.Unmarshal(listRec.Body.Bytes(), &feed); err != nil {
		t.Fatal(err)
	}
	if feed.SchemaVersion != 1 || len(feed.Pins) != 2 {
		t.Fatalf("unexpected feed: %+v", feed)
	}

	// 8. Validation errors -> 400 with details.
	badVal := env.do(t, http.MethodPost, "/api/v1/pins", testWriteToken, `{
		"title": "",
		"startTime": "bad-date",
		"durationMinutes": 9999,
		"iconUri": "http://bad",
		"backgroundColor": "not-hex",
		"layoutType": "NOPE"
	}`)
	if badVal.Code != http.StatusBadRequest {
		t.Fatalf("invalid pin status = %d, want 400", badVal.Code)
	}
	var errResp apiErrorResponse
	if err := json.Unmarshal(badVal.Body.Bytes(), &errResp); err != nil {
		t.Fatal(err)
	}
	if errResp.Error != "validation_failed" || len(errResp.Details) < 5 {
		t.Fatalf("expected structured validation errors, got: %+v", errResp)
	}

	// 9. Unknown JSON field rejected -> 400.
	unknownField := env.do(t, http.MethodPost, "/api/v1/pins", testWriteToken, `{
		"title": "Valid",
		"startTime": "2026-11-26T14:00:00Z",
		"unexpectedField": true
	}`)
	if unknownField.Code != http.StatusBadRequest {
		t.Fatalf("unknown field status = %d, want 400", unknownField.Code)
	}

	// 10. Wrong Content-Type -> 415.
	req := httptest.NewRequest(http.MethodPost, "/api/v1/pins", strings.NewReader("title=hi"))
	req.Header.Set("Authorization", "Bearer "+testWriteToken)
	req.Header.Set("Content-Type", "text/plain")
	wrongCT := httptest.NewRecorder()
	env.server.ServeHTTP(wrongCT, req)
	if wrongCT.Code != http.StatusUnsupportedMediaType {
		t.Fatalf("wrong Content-Type status = %d, want 415", wrongCT.Code)
	}

	// 11. Delete pin -> 204, second delete -> 404, GET -> 404.
	delRec := env.do(t, http.MethodDelete, "/api/v1/pins/ext-deploy-1", testWriteToken, "")
	if delRec.Code != http.StatusNoContent {
		t.Fatalf("DELETE status = %d, want 204", delRec.Code)
	}
	delAgain := env.do(t, http.MethodDelete, "/api/v1/pins/ext-deploy-1", testWriteToken, "")
	if delAgain.Code != http.StatusNotFound {
		t.Fatalf("second DELETE status = %d, want 404", delAgain.Code)
	}
	getDeleted := env.do(t, http.MethodGet, "/api/v1/pins/ext-deploy-1", testReadToken, "")
	if getDeleted.Code != http.StatusNotFound {
		t.Fatalf("GET deleted status = %d, want 404", getDeleted.Code)
	}
}

func TestFeed_ETagLastModifiedAndPersistenceAcrossRestart(t *testing.T) {
	env := newTestEnv(t, 48*time.Hour)

	// Seed a pin.
	createRec := env.do(t, http.MethodPut, "/api/v1/pins/sync-1", testWriteToken, `{
		"title": "Flight Check-in",
		"startTime": "2026-11-26T14:00:00Z",
		"durationMinutes": 45,
		"iconUri": "system://images/SCHEDULED_FLIGHT",
		"layoutType": "CALENDAR_PIN"
	}`)
	if createRec.Code != http.StatusCreated {
		t.Fatalf("seed pin status = %d (%s)", createRec.Code, createRec.Body.String())
	}

	// Both /pins.json and /api/v1/pins.json serve identical feed & ETag.
	feed1 := env.do(t, http.MethodGet, "/pins.json", testReadToken, "")
	feed2 := env.do(t, http.MethodGet, "/api/v1/pins.json", testReadToken, "")
	if feed1.Code != http.StatusOK || feed2.Code != http.StatusOK {
		t.Fatalf("feed statuses: %d, %d", feed1.Code, feed2.Code)
	}
	etag := feed1.Header().Get("ETag")
	lastMod := feed1.Header().Get("Last-Modified")
	if etag == "" || lastMod == "" {
		t.Fatalf("missing caching headers: ETag=%q Last-Modified=%q", etag, lastMod)
	}
	if etag != feed2.Header().Get("ETag") || feed1.Body.String() != feed2.Body.String() {
		t.Fatalf("/pins.json and /api/v1/pins.json diverged")
	}
	if cc := feed1.Header().Get("Cache-Control"); cc != "private, no-cache" {
		t.Errorf("Cache-Control = %q, want private, no-cache", cc)
	}

	// If-None-Match exact, weak, comma list, and wildcard -> 304 Not Modified.
	for _, inm := range []string{etag, "W/" + etag, `"other", ` + etag, "*"} {
		req := httptest.NewRequest(http.MethodGet, "/pins.json", nil)
		req.Header.Set("Authorization", "Bearer "+testReadToken)
		req.Header.Set("If-None-Match", inm)
		rec := httptest.NewRecorder()
		env.server.ServeHTTP(rec, req)
		if rec.Code != http.StatusNotModified {
			t.Errorf("If-None-Match %q status = %d, want 304", inm, rec.Code)
		}
		if rec.Body.Len() != 0 {
			t.Errorf("304 response must have empty body, got %d bytes", rec.Body.Len())
		}
		if rec.Header().Get("ETag") != etag {
			t.Errorf("304 response ETag = %q, want %q", rec.Header().Get("ETag"), etag)
		}
	}

	// If-None-Match non-matching -> 200 OK.
	req := httptest.NewRequest(http.MethodGet, "/pins.json", nil)
	req.Header.Set("Authorization", "Bearer "+testReadToken)
	req.Header.Set("If-None-Match", `"stale-tag"`)
	mismatchRec := httptest.NewRecorder()
	env.server.ServeHTTP(mismatchRec, req)
	if mismatchRec.Code != http.StatusOK {
		t.Fatalf("mismatched If-None-Match status = %d, want 200", mismatchRec.Code)
	}

	// If-Modified-Since -> 304 Not Modified.
	req = httptest.NewRequest(http.MethodGet, "/pins.json", nil)
	req.Header.Set("Authorization", "Bearer "+testReadToken)
	req.Header.Set("If-Modified-Since", lastMod)
	imsRec := httptest.NewRecorder()
	env.server.ServeHTTP(imsRec, req)
	if imsRec.Code != http.StatusNotModified {
		t.Fatalf("If-Modified-Since status = %d, want 304", imsRec.Code)
	}

	// HEAD /pins.json returns headers without body.
	headRec := env.do(t, http.MethodHead, "/pins.json", testReadToken, "")
	if headRec.Code != http.StatusOK || headRec.Body.Len() != 0 || headRec.Header().Get("ETag") != etag {
		t.Fatalf("HEAD /pins.json status=%d bodyLen=%d etag=%q", headRec.Code, headRec.Body.Len(), headRec.Header().Get("ETag"))
	}

	// Simulate full server restart from persisted JSON file: ETag must still match -> 304.
	restartedStore, err := store.New(env.dataFile, 48*time.Hour, store.WithClock(func() time.Time { return *env.now }))
	if err != nil {
		t.Fatalf("restart store.New: %v", err)
	}
	restartedServer := NewServer(restartedStore, NewAuthenticator(testWriteToken, testReadToken))
	req = httptest.NewRequest(http.MethodGet, "/pins.json", nil)
	req.Header.Set("Authorization", "Bearer "+testReadToken)
	req.Header.Set("If-None-Match", etag)
	restartRec := httptest.NewRecorder()
	restartedServer.ServeHTTP(restartRec, req)
	if restartRec.Code != http.StatusNotModified {
		t.Fatalf("after restart with matching ETag status = %d, want 304", restartRec.Code)
	}

	// Mutating the pin changes the ETag so old If-None-Match returns 200 OK.
	*env.now = env.now.Add(5 * time.Minute)
	updRec := env.do(t, http.MethodPut, "/api/v1/pins/sync-1", testWriteToken, `{
		"title": "Flight Check-in (Gate B4)",
		"startTime": "2026-11-26T14:00:00Z"
	}`)
	if updRec.Code != http.StatusOK {
		t.Fatalf("update status = %d", updRec.Code)
	}
	req = httptest.NewRequest(http.MethodGet, "/pins.json", nil)
	req.Header.Set("Authorization", "Bearer "+testReadToken)
	req.Header.Set("If-None-Match", etag)
	afterUpd := httptest.NewRecorder()
	env.server.ServeHTTP(afterUpd, req)
	if afterUpd.Code != http.StatusOK {
		t.Fatalf("after mutation old If-None-Match status = %d, want 200", afterUpd.Code)
	}
	if afterUpd.Header().Get("ETag") == etag {
		t.Errorf("expected new ETag after mutation")
	}
}
