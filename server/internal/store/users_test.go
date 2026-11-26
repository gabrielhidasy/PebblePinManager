package store

import (
	"crypto/sha256"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/gabrielhidasy/PebblePinManager/server/internal/model"
)

func TestUserManager_CRUDAuthIsolationAndReload(t *testing.T) {
	dataDir := t.TempDir()
	now := time.Date(2026, 11, 26, 12, 0, 0, 0, time.UTC)
	clock := func() time.Time { return now }

	mgr, err := NewUserManager(dataDir, 48*time.Hour, WithUserClock(clock), WithMaxUsers(2))
	if err != nil {
		t.Fatalf("NewUserManager: %v", err)
	}

	alice, err := mgr.CreateUser("Alice's Pebble")
	if err != nil {
		t.Fatalf("CreateUser alice: %v", err)
	}
	if len(alice.User.ID) != 32 || alice.User.Name != "Alice's Pebble" {
		t.Fatalf("unexpected alice summary: %+v", alice.User)
	}
	if alice.ReadToken == "" || alice.WriteToken == "" || alice.ReadToken == alice.WriteToken {
		t.Fatalf("expected distinct non-empty read/write tokens")
	}

	now = now.Add(1 * time.Minute)
	bob, err := mgr.CreateUser("Bob's Pebble")
	if err != nil {
		t.Fatalf("CreateUser bob: %v", err)
	}
	if bob.User.ID == alice.User.ID {
		t.Fatalf("expected distinct user IDs")
	}

	// Max users limit enforced.
	if _, err := mgr.CreateUser("Charlie"); !errors.Is(err, ErrUserLimitReached) {
		t.Fatalf("3rd user err = %v, want ErrUserLimitReached", err)
	}

	// Verify plaintext tokens are NEVER written to users.json on disk.
	rawUsers, err := os.ReadFile(filepath.Join(dataDir, "users.json"))
	if err != nil {
		t.Fatalf("read users.json: %v", err)
	}
	if strings.Contains(string(rawUsers), alice.ReadToken) || strings.Contains(string(rawUsers), alice.WriteToken) ||
		strings.Contains(string(rawUsers), bob.ReadToken) || strings.Contains(string(rawUsers), bob.WriteToken) {
		t.Fatalf("plaintext token leaked into users.json!")
	}

	// Authenticate checks per-user scoping.
	mw, mr, exists := mgr.AuthenticateUser(alice.User.ID, sha256.Sum256([]byte(alice.ReadToken)))
	if !exists || mw || !mr {
		t.Fatalf("alice read token auth = (%v,%v,%v), want (false,true,true)", mw, mr, exists)
	}
	mw, mr, exists = mgr.AuthenticateUser(alice.User.ID, sha256.Sum256([]byte(alice.WriteToken)))
	if !exists || !mw || mr {
		t.Fatalf("alice write token auth = (%v,%v,%v), want (true,false,true)", mw, mr, exists)
	}
	// Cross-user token must NOT match.
	mw, mr, exists = mgr.AuthenticateUser(bob.User.ID, sha256.Sum256([]byte(alice.WriteToken)))
	if !exists || mw || mr {
		t.Fatalf("alice token on bob = (%v,%v,%v), want (false,false,true)", mw, mr, exists)
	}
	// Non-existent user.
	mw, mr, exists = mgr.AuthenticateUser("00000000000000000000000000000000", sha256.Sum256([]byte(alice.WriteToken)))
	if exists || mw || mr {
		t.Fatalf("missing user auth = (%v,%v,%v), want (false,false,false)", mw, mr, exists)
	}

	// Isolated per-user pin stores.
	aliceStore, err := mgr.PinStore(alice.User.ID)
	if err != nil {
		t.Fatalf("PinStore alice: %v", err)
	}
	bobStore, err := mgr.PinStore(bob.User.ID)
	if err != nil {
		t.Fatalf("PinStore bob: %v", err)
	}

	if _, err := aliceStore.Create(model.Pin{
		ID:         "shared-pin-id",
		Title:      "Alice Only",
		StartTime:  "2026-11-27T14:00:00Z",
		IconURI:    model.DefaultIconURI,
		LayoutType: model.LayoutGenericPin,
	}); err != nil {
		t.Fatalf("alice create pin: %v", err)
	}
	if _, err := bobStore.Create(model.Pin{
		ID:         "shared-pin-id",
		Title:      "Bob Only",
		StartTime:  "2026-11-27T15:00:00Z",
		IconURI:    model.DefaultIconURI,
		LayoutType: model.LayoutGenericPin,
	}); err != nil {
		t.Fatalf("bob create pin with same ID: %v", err)
	}

	// Reload from disk and verify users, auth, and pin isolation persist.
	reloaded, err := NewUserManager(dataDir, 48*time.Hour, WithUserClock(clock))
	if err != nil {
		t.Fatalf("reload UserManager: %v", err)
	}
	listed := reloaded.ListUsers()
	if len(listed) != 2 || listed[0].ID != alice.User.ID || listed[1].ID != bob.User.ID {
		t.Fatalf("reloaded ListUsers = %+v", listed)
	}
	mw, mr, exists = reloaded.AuthenticateUser(bob.User.ID, sha256.Sum256([]byte(bob.ReadToken)))
	if !exists || mw || !mr {
		t.Fatalf("reloaded bob read auth failed: (%v,%v,%v)", mw, mr, exists)
	}
	reloadedAliceStore, err := reloaded.PinStore(alice.User.ID)
	if err != nil {
		t.Fatalf("reloaded PinStore alice: %v", err)
	}
	gotPin, ok := reloadedAliceStore.Get("shared-pin-id")
	if !ok || gotPin.Title != "Alice Only" {
		t.Fatalf("reloaded alice pin = (%+v, %v)", gotPin, ok)
	}

	// Delete user removes user and their data directory.
	if err := reloaded.DeleteUser(alice.User.ID); err != nil {
		t.Fatalf("DeleteUser alice: %v", err)
	}
	if _, ok := reloaded.GetUser(alice.User.ID); ok {
		t.Fatalf("deleted user still returned by GetUser")
	}
	if _, err := reloaded.PinStore(alice.User.ID); !errors.Is(err, ErrUserNotFound) {
		t.Fatalf("PinStore deleted user err = %v, want ErrUserNotFound", err)
	}
	if _, err := os.Stat(filepath.Join(dataDir, "users", alice.User.ID)); !os.IsNotExist(err) {
		t.Fatalf("expected user data dir to be deleted, stat err = %v", err)
	}
	if err := reloaded.DeleteUser(alice.User.ID); !errors.Is(err, ErrUserNotFound) {
		t.Fatalf("second DeleteUser err = %v, want ErrUserNotFound", err)
	}
}

func TestUserManager_CorruptUsersFileFailsFast(t *testing.T) {
	dataDir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dataDir, "users.json"), []byte("{corrupt"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := NewUserManager(dataDir, 48*time.Hour); err == nil {
		t.Fatalf("expected error loading corrupt users.json")
	}
}

func TestUserManager_SubjectProvisioningRevealRotateAndTokenLookup(t *testing.T) {
	dataDir := t.TempDir()
	now := time.Date(2026, 11, 26, 12, 0, 0, 0, time.UTC)
	clock := func() time.Time { return now }

	mgr, err := NewUserManager(dataDir, 48*time.Hour, WithUserClock(clock))
	if err != nil {
		t.Fatalf("NewUserManager: %v", err)
	}

	// Provision by external subject (Authelia Remote-User).
	u1, created, err := mgr.GetOrCreateUserBySubject("Gabriel", "Gabriel Hidasy")
	if err != nil {
		t.Fatalf("GetOrCreateUserBySubject: %v", err)
	}
	if !created {
		t.Fatalf("expected created=true on first call")
	}
	if u1.ExternalSubject != "gabriel" || u1.Name != "Gabriel Hidasy" {
		t.Fatalf("unexpected user summary: %+v", u1)
	}

	// Case-insensitive second call returns the same user idempotently.
	u2, created, err := mgr.GetOrCreateUserBySubject("  GABRIEL  ", "Ignored Name")
	if err != nil {
		t.Fatalf("second GetOrCreateUserBySubject: %v", err)
	}
	if created || u2.ID != u1.ID {
		t.Fatalf("expected idempotent match, got created=%v id=%q (want %q)", created, u2.ID, u1.ID)
	}

	// Reveal sealed tokens and verify authentication + lookup.
	readTok, writeTok, avail, err := mgr.RevealTokens(u1.ID)
	if err != nil || !avail || readTok == "" || writeTok == "" || readTok == writeTok {
		t.Fatalf("RevealTokens = (%q, %q, %v, %v)", readTok, writeTok, avail, err)
	}

	// Verify plaintext tokens never appear in users.json.
	rawUsers, err := os.ReadFile(filepath.Join(dataDir, "users.json"))
	if err != nil {
		t.Fatalf("read users.json: %v", err)
	}
	if strings.Contains(string(rawUsers), readTok) || strings.Contains(string(rawUsers), writeTok) {
		t.Fatalf("plaintext token leaked into users.json")
	}

	// FindUserByWriteToken accepts write token, rejects read token and wrong tokens.
	if found, ok := mgr.FindUserByWriteToken(sha256.Sum256([]byte(writeTok))); !ok || found.ID != u1.ID {
		t.Fatalf("FindUserByWriteToken(writeTok) = (%+v, %v), want %q", found, ok, u1.ID)
	}
	if _, ok := mgr.FindUserByWriteToken(sha256.Sum256([]byte(readTok))); ok {
		t.Fatalf("FindUserByWriteToken must reject read token")
	}
	if _, ok := mgr.FindUserByWriteToken(sha256.Sum256([]byte("wrong-token"))); ok {
		t.Fatalf("FindUserByWriteToken must reject wrong token")
	}

	// Rotate write token only: read token remains unchanged, old write token stops working.
	readAfterWriteRot, newWriteTok, err := mgr.RotateTokens(u1.ID, false, true)
	if err != nil {
		t.Fatalf("RotateTokens write: %v", err)
	}
	if readAfterWriteRot != readTok {
		t.Fatalf("read token changed during write-only rotation")
	}
	if newWriteTok == writeTok || newWriteTok == "" {
		t.Fatalf("expected new write token after rotation")
	}
	if mw, _, _ := mgr.AuthenticateUser(u1.ID, sha256.Sum256([]byte(writeTok))); mw {
		t.Fatalf("old write token still valid after rotation")
	}
	if mw, _, _ := mgr.AuthenticateUser(u1.ID, sha256.Sum256([]byte(newWriteTok))); !mw {
		t.Fatalf("new write token rejected after rotation")
	}

	// Rotate read token only: write token remains unchanged, old read token stops working.
	newReadTok, writeAfterReadRot, err := mgr.RotateTokens(u1.ID, true, false)
	if err != nil {
		t.Fatalf("RotateTokens read: %v", err)
	}
	if writeAfterReadRot != newWriteTok {
		t.Fatalf("write token changed during read-only rotation")
	}
	if newReadTok == readTok || newReadTok == "" {
		t.Fatalf("expected new read token after rotation")
	}
	if _, mr, _ := mgr.AuthenticateUser(u1.ID, sha256.Sum256([]byte(readTok))); mr {
		t.Fatalf("old read token still valid after rotation")
	}

	// Update display name.
	updated, err := mgr.UpdateUserName(u1.ID, "My Pebble Time 2")
	if err != nil || updated.Name != "My Pebble Time 2" {
		t.Fatalf("UpdateUserName = (%+v, %v)", updated, err)
	}

	// Reload from disk and verify seal key + subject + revealed tokens persist.
	reloaded, err := NewUserManager(dataDir, 48*time.Hour, WithUserClock(clock))
	if err != nil {
		t.Fatalf("reload UserManager: %v", err)
	}
	gotRead, gotWrite, gotAvail, err := reloaded.RevealTokens(u1.ID)
	if err != nil || !gotAvail || gotRead != newReadTok || gotWrite != newWriteTok {
		t.Fatalf("reloaded RevealTokens = (%q, %q, %v, %v)", gotRead, gotWrite, gotAvail, err)
	}
	u3, created3, err := reloaded.GetOrCreateUserBySubject("gabriel", "")
	if err != nil || created3 || u3.ID != u1.ID || u3.Name != "My Pebble Time 2" {
		t.Fatalf("reloaded subject lookup = (%+v, %v, %v)", u3, created3, err)
	}
}

func TestUserManager_LegacyUnsealedUserCompatibilityAndRotation(t *testing.T) {
	dataDir := t.TempDir()
	readHash := sha256.Sum256([]byte("legacy-read-token"))
	writeHash := sha256.Sum256([]byte("legacy-write-token"))

	legacyJSON := `{
  "schemaVersion": 1,
  "users": [
    {
      "id": "a1b2c3d4e5f60718293a4b5c6d7e8f90",
      "name": "Legacy User",
      "createdAt": "2026-11-26T12:00:00Z",
      "readTokenHash": "` + encodeHex(readHash[:]) + `",
      "writeTokenHash": "` + encodeHex(writeHash[:]) + `"
    }
  ]
}`
	if err := os.WriteFile(filepath.Join(dataDir, "users.json"), []byte(legacyJSON), 0o600); err != nil {
		t.Fatal(err)
	}

	mgr, err := NewUserManager(dataDir, 48*time.Hour, WithTokenEncryptionKey("explicit-test-key"))
	if err != nil {
		t.Fatalf("NewUserManager legacy: %v", err)
	}

	_, _, avail, err := mgr.RevealTokens("a1b2c3d4e5f60718293a4b5c6d7e8f90")
	if err != nil || avail {
		t.Fatalf("legacy RevealTokens = (avail=%v, err=%v), want (false, nil)", avail, err)
	}

	newRead, newWrite, err := mgr.RotateTokens("a1b2c3d4e5f60718293a4b5c6d7e8f90", false, true)
	if err != nil || newRead == "" || newWrite == "" {
		t.Fatalf("legacy RotateTokens = (%q, %q, %v)", newRead, newWrite, err)
	}

	gotRead, gotWrite, avail, err := mgr.RevealTokens("a1b2c3d4e5f60718293a4b5c6d7e8f90")
	if err != nil || !avail || gotRead != newRead || gotWrite != newWrite {
		t.Fatalf("post-rotate RevealTokens = (%q, %q, %v, %v)", gotRead, gotWrite, avail, err)
	}
}

func encodeHex(b []byte) string {
	const digits = "0123456789abcdef"
	out := make([]byte, len(b)*2)
	for i, v := range b {
		out[i*2] = digits[v>>4]
		out[i*2+1] = digits[v&0x0f]
	}
	return string(out)
}
