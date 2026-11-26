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

type multiUserTestEnv struct {
	server  *Server
	dataDir string
	logs    *bytes.Buffer
	now     *time.Time
}

func newMultiUserTestEnv(t *testing.T) *multiUserTestEnv {
	t.Helper()
	dataDir := t.TempDir()
	now := time.Date(2026, 11, 25, 12, 0, 0, 0, time.UTC)
	clock := func() time.Time { return now }

	st, err := store.New(filepath.Join(dataDir, "pins.json"), 48*time.Hour, store.WithClock(clock))
	if err != nil {
		t.Fatalf("store.New: %v", err)
	}
	mgr, err := store.NewUserManager(dataDir, 48*time.Hour, store.WithUserClock(clock), store.WithMaxUsers(100))
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
		WithServerClock(clock),
		WithUserManager(mgr),
		WithPublicURL("https://pins.example.com"),
		WithServerName("Home Pin Server"),
	)

	return &multiUserTestEnv{
		server:  srv,
		dataDir: dataDir,
		logs:    &logBuf,
		now:     &now,
	}
}

func (e *multiUserTestEnv) do(t *testing.T, method, path, token, body string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(method, path, strings.NewReader(body))
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

func TestMultiUser_AdminCRUDIsolationAuthAndRestartPersistence(t *testing.T) {
	env := newMultiUserTestEnv(t)

	// Registration mode is disabled, but admin can still manage users via /api/v1/users.
	createA := env.do(t, http.MethodPost, "/api/v1/users", testWriteToken, `{"name":"Alice"}`)
	if createA.Code != http.StatusCreated {
		t.Fatalf("create Alice status = %d (%s), want 201", createA.Code, createA.Body.String())
	}
	var alice userCreatedResponse
	if err := json.Unmarshal(createA.Body.Bytes(), &alice); err != nil {
		t.Fatalf("decode Alice: %v", err)
	}
	if createA.Header().Get("Location") != "/api/v1/users/"+alice.ID {
		t.Errorf("Location = %q", createA.Header().Get("Location"))
	}

	*env.now = env.now.Add(1 * time.Minute)
	createB := env.do(t, http.MethodPost, "/api/v1/users", testWriteToken, `{"name":"Bob"}`)
	if createB.Code != http.StatusCreated {
		t.Fatalf("create Bob status = %d, want 201", createB.Code)
	}
	var bob userCreatedResponse
	if err := json.Unmarshal(createB.Body.Bytes(), &bob); err != nil {
		t.Fatalf("decode Bob: %v", err)
	}

	// Admin list/get users never expose tokens or hashes.
	listRec := env.do(t, http.MethodGet, "/api/v1/users", testWriteToken, "")
	if listRec.Code != http.StatusOK {
		t.Fatalf("list users status = %d, want 200", listRec.Code)
	}
	listBody := listRec.Body.String()
	for _, secret := range []string{alice.ReadToken, alice.WriteToken, bob.ReadToken, bob.WriteToken, "Hash"} {
		if strings.Contains(listBody, secret) {
			t.Fatalf("list users leaked sensitive field %q", secret)
		}
	}
	var listed listUsersResponse
	if err := json.Unmarshal(listRec.Body.Bytes(), &listed); err != nil {
		t.Fatal(err)
	}
	if len(listed.Users) != 2 || listed.Users[0].ID != alice.ID || listed.Users[1].ID != bob.ID {
		t.Fatalf("unexpected listed users: %+v", listed.Users)
	}

	getAlice := env.do(t, http.MethodGet, "/api/v1/users/"+alice.ID, testWriteToken, "")
	if getAlice.Code != http.StatusOK {
		t.Fatalf("get Alice status = %d, want 200", getAlice.Code)
	}

	// Alice writes a pin with her own write token.
	pinPayloadAlice := `{"title":"Alice Meeting","startTime":"2026-11-26T14:00:00Z","durationMinutes":30}`
	putAlice := env.do(t, http.MethodPut, "/api/v1/users/"+alice.ID+"/pins/shared-id", alice.WriteToken, pinPayloadAlice)
	if putAlice.Code != http.StatusCreated {
		t.Fatalf("Alice PUT pin = %d (%s), want 201", putAlice.Code, putAlice.Body.String())
	}
	if putAlice.Header().Get("Location") != "/api/v1/users/"+alice.ID+"/pins/shared-id" {
		t.Errorf("Alice Location = %q", putAlice.Header().Get("Location"))
	}

	// Bob writes a pin with the SAME pin ID using Bob's write token.
	pinPayloadBob := `{"title":"Bob Dentist","startTime":"2026-11-26T15:00:00Z"}`
	putBob := env.do(t, http.MethodPut, "/api/v1/users/"+bob.ID+"/pins/shared-id", bob.WriteToken, pinPayloadBob)
	if putBob.Code != http.StatusCreated {
		t.Fatalf("Bob PUT pin = %d (%s), want 201", putBob.Code, putBob.Body.String())
	}

	// Legacy single-user store remains completely separate.
	legacyFeedRec := env.do(t, http.MethodGet, "/pins.json", testReadToken, "")
	if legacyFeedRec.Code != http.StatusOK {
		t.Fatalf("legacy /pins.json = %d, want 200", legacyFeedRec.Code)
	}
	var legacyFeed model.PinFeed
	if err := json.Unmarshal(legacyFeedRec.Body.Bytes(), &legacyFeed); err != nil {
		t.Fatal(err)
	}
	if len(legacyFeed.Pins) != 0 {
		t.Fatalf("legacy feed should have 0 pins, got %d", len(legacyFeed.Pins))
	}

	// Alice fetches her feed via /<aliceId>/pins.json and /api/v1/users/<aliceId>/pins.json with ETag / 304.
	aliceFeed1 := env.do(t, http.MethodGet, "/"+alice.ID+"/pins.json", alice.ReadToken, "")
	aliceFeed2 := env.do(t, http.MethodGet, "/api/v1/users/"+alice.ID+"/pins.json", alice.ReadToken, "")
	if aliceFeed1.Code != http.StatusOK || aliceFeed2.Code != http.StatusOK {
		t.Fatalf("Alice feed statuses: %d, %d", aliceFeed1.Code, aliceFeed2.Code)
	}
	if aliceFeed1.Header().Get("ETag") == "" || aliceFeed1.Header().Get("ETag") != aliceFeed2.Header().Get("ETag") {
		t.Fatalf("Alice ETag mismatch: %q vs %q", aliceFeed1.Header().Get("ETag"), aliceFeed2.Header().Get("ETag"))
	}
	var parsedAliceFeed model.PinFeed
	if err := json.Unmarshal(aliceFeed1.Body.Bytes(), &parsedAliceFeed); err != nil {
		t.Fatal(err)
	}
	if len(parsedAliceFeed.Pins) != 1 || parsedAliceFeed.Pins[0].Title != "Alice Meeting" {
		t.Fatalf("unexpected Alice feed: %+v", parsedAliceFeed)
	}

	// Conditional If-None-Match -> 304 Not Modified on /<aliceId>/pins.json.
	inmReq := httptest.NewRequest(http.MethodGet, "/"+alice.ID+"/pins.json", nil)
	inmReq.Header.Set("Authorization", "Bearer "+alice.ReadToken)
	inmReq.Header.Set("If-None-Match", aliceFeed1.Header().Get("ETag"))
	inmRec := httptest.NewRecorder()
	env.server.ServeHTTP(inmRec, inmReq)
	if inmRec.Code != http.StatusNotModified {
		t.Fatalf("Alice If-None-Match status = %d, want 304", inmRec.Code)
	}

	// Bob only sees Bob's pin.
	bobFeedRec := env.do(t, http.MethodGet, "/"+bob.ID+"/pins.json", bob.ReadToken, "")
	if bobFeedRec.Code != http.StatusOK {
		t.Fatalf("Bob feed status = %d, want 200", bobFeedRec.Code)
	}
	var parsedBobFeed model.PinFeed
	if err := json.Unmarshal(bobFeedRec.Body.Bytes(), &parsedBobFeed); err != nil {
		t.Fatal(err)
	}
	if len(parsedBobFeed.Pins) != 1 || parsedBobFeed.Pins[0].Title != "Bob Dentist" {
		t.Fatalf("unexpected Bob feed: %+v", parsedBobFeed)
	}

	// Strict auth isolation & anti-enumeration checks:
	nonexistentID := "00000000000000000000000000000000"
	authChecks := []struct {
		name   string
		method string
		path   string
		token  string
		body   string
		want   int
	}{
		{"unauth existing user feed", http.MethodGet, "/" + alice.ID + "/pins.json", "", "", http.StatusUnauthorized},
		{"unauth missing user feed", http.MethodGet, "/" + nonexistentID + "/pins.json", "", "", http.StatusUnauthorized},
		{"alice read token on alice POST", http.MethodPost, "/api/v1/users/" + alice.ID + "/pins", alice.ReadToken, pinPayloadAlice, http.StatusForbidden},
		{"alice read token on alice PUT", http.MethodPut, "/api/v1/users/" + alice.ID + "/pins/p2", alice.ReadToken, pinPayloadAlice, http.StatusForbidden},
		{"alice read token on alice DELETE", http.MethodDelete, "/api/v1/users/" + alice.ID + "/pins/shared-id", alice.ReadToken, "", http.StatusForbidden},
		{"alice write token on bob feed", http.MethodGet, "/" + bob.ID + "/pins.json", alice.WriteToken, "", http.StatusUnauthorized},
		{"alice write token on missing feed", http.MethodGet, "/" + nonexistentID + "/pins.json", alice.WriteToken, "", http.StatusUnauthorized},
		{"alice write token on bob pins", http.MethodPost, "/api/v1/users/" + bob.ID + "/pins", alice.WriteToken, pinPayloadAlice, http.StatusUnauthorized},
		{"alice write token on legacy feed", http.MethodGet, "/pins.json", alice.WriteToken, "", http.StatusUnauthorized},
		{"alice write token on admin users", http.MethodGet, "/api/v1/users", alice.WriteToken, "", http.StatusUnauthorized},
		{"global read token on alice feed", http.MethodGet, "/" + alice.ID + "/pins.json", testReadToken, "", http.StatusForbidden},
		{"global read token on missing feed", http.MethodGet, "/" + nonexistentID + "/pins.json", testReadToken, "", http.StatusForbidden},
		{"global read token on admin users", http.MethodGet, "/api/v1/users", testReadToken, "", http.StatusForbidden},
		{"global read token on admin create", http.MethodPost, "/api/v1/users", testReadToken, `{}`, http.StatusForbidden},
		{"admin on alice feed", http.MethodGet, "/" + alice.ID + "/pins.json", testWriteToken, "", http.StatusOK},
		{"admin on missing feed", http.MethodGet, "/" + nonexistentID + "/pins.json", testWriteToken, "", http.StatusNotFound},
		{"admin on missing user metadata", http.MethodGet, "/api/v1/users/" + nonexistentID, testWriteToken, "", http.StatusNotFound},
	}
	for _, tc := range authChecks {
		rec := env.do(t, tc.method, tc.path, tc.token, tc.body)
		if rec.Code != tc.want {
			t.Errorf("%s (%s %s) = %d, want %d (body=%s)", tc.name, tc.method, tc.path, rec.Code, tc.want, rec.Body.String())
		}
	}

	// Simulate full server restart from disk: Alice's ETag and tokens still work.
	restartedLegacy, err := store.New(filepath.Join(env.dataDir, "pins.json"), 48*time.Hour, store.WithClock(func() time.Time { return *env.now }))
	if err != nil {
		t.Fatal(err)
	}
	restartedUsers, err := store.NewUserManager(env.dataDir, 48*time.Hour, store.WithUserClock(func() time.Time { return *env.now }))
	if err != nil {
		t.Fatal(err)
	}
	restartedServer := NewServer(restartedLegacy, NewAuthenticator(testWriteToken, testReadToken), WithUserManager(restartedUsers))
	restartReq := httptest.NewRequest(http.MethodGet, "/"+alice.ID+"/pins.json", nil)
	restartReq.Header.Set("Authorization", "Bearer "+alice.ReadToken)
	restartReq.Header.Set("If-None-Match", aliceFeed1.Header().Get("ETag"))
	restartRec := httptest.NewRecorder()
	restartedServer.ServeHTTP(restartRec, restartReq)
	if restartRec.Code != http.StatusNotModified {
		t.Fatalf("after restart Alice If-None-Match = %d, want 304", restartRec.Code)
	}

	// Admin deletes Alice -> Alice's token stops working, Bob stays intact.
	delAlice := env.do(t, http.MethodDelete, "/api/v1/users/"+alice.ID, testWriteToken, "")
	if delAlice.Code != http.StatusNoContent {
		t.Fatalf("DELETE Alice = %d, want 204", delAlice.Code)
	}
	afterDelAlice := env.do(t, http.MethodGet, "/"+alice.ID+"/pins.json", alice.ReadToken, "")
	if afterDelAlice.Code != http.StatusUnauthorized {
		t.Fatalf("Alice token after delete = %d, want 401", afterDelAlice.Code)
	}
	afterDelAdmin := env.do(t, http.MethodGet, "/"+alice.ID+"/pins.json", testWriteToken, "")
	if afterDelAdmin.Code != http.StatusNotFound {
		t.Fatalf("admin on deleted Alice = %d, want 404", afterDelAdmin.Code)
	}
	bobStillOK := env.do(t, http.MethodGet, "/"+bob.ID+"/pins.json", bob.ReadToken, "")
	if bobStillOK.Code != http.StatusOK {
		t.Fatalf("Bob feed after Alice delete = %d, want 200", bobStillOK.Code)
	}

	// Verify no secrets ever leaked into logs.
	logStr := env.logs.String()
	for _, secret := range []string{testWriteToken, testReadToken, alice.ReadToken, alice.WriteToken, bob.ReadToken, bob.WriteToken} {
		if strings.Contains(logStr, secret) {
			t.Fatalf("secret leaked into server logs")
		}
	}
}
