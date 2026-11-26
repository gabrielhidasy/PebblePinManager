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
