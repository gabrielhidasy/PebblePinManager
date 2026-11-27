package store

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"github.com/gabrielhidasy/PebblePinManager/server/internal/model"
)

func intPtr(i int) *int { return &i }

func TestStore_CRUDAndPersistenceAcrossReloads(t *testing.T) {
	dataFile := filepath.Join(t.TempDir(), "state", "pins.json")
	current := time.Date(2026, 11, 25, 12, 0, 0, 0, time.UTC)
	clock := func() time.Time { return current }

	s, err := New(dataFile, 48*time.Hour, WithClock(clock))
	if err != nil {
		t.Fatalf("New store: %v", err)
	}

	emptySnap, err := s.Feed()
	if err != nil {
		t.Fatalf("Feed empty: %v", err)
	}
	if emptySnap.Feed.SchemaVersion != model.SchemaVersion || len(emptySnap.Feed.Pins) != 0 {
		t.Fatalf("unexpected empty feed: %+v", emptySnap.Feed)
	}

	p1 := model.Pin{
		ID:              "pin-1",
		Title:           "Standup",
		StartTime:       "2026-11-26T14:00:00Z",
		DurationMinutes: intPtr(30),
		IconURI:         model.DefaultIconURI,
		LayoutType:      model.LayoutCalendarPin,
	}
	if _, err := s.Create(p1); err != nil {
		t.Fatalf("Create p1: %v", err)
	}
	if _, err := s.Create(p1); !errors.Is(err, ErrAlreadyExists) {
		t.Fatalf("duplicate Create err = %v, want ErrAlreadyExists", err)
	}

	current = current.Add(1 * time.Minute)
	p1Updated := p1
	p1Updated.Title = "Team Standup (Updated)"
	_, created, err := s.Upsert(p1Updated)
	if err != nil || created {
		t.Fatalf("Upsert existing: created=%v err=%v", created, err)
	}

	p2 := model.Pin{
		ID:         "pin-2",
		Title:      "Earlier Pin",
		StartTime:  "2026-11-26T09:00:00Z",
		IconURI:    model.DefaultIconURI,
		LayoutType: model.LayoutGenericPin,
	}
	_, created, err = s.Upsert(p2)
	if err != nil || !created {
		t.Fatalf("Upsert new: created=%v err=%v", created, err)
	}

	snapBeforeReload, err := s.Feed()
	if err != nil {
		t.Fatalf("Feed before reload: %v", err)
	}
	if len(snapBeforeReload.Feed.Pins) != 2 {
		t.Fatalf("expected 2 pins, got %d", len(snapBeforeReload.Feed.Pins))
	}
	// Deterministic sort by startTime ascending
	if snapBeforeReload.Feed.Pins[0].ID != "pin-2" || snapBeforeReload.Feed.Pins[1].ID != "pin-1" {
		t.Fatalf("unexpected pin order: %+v", snapBeforeReload.Feed.Pins)
	}

	// Simulate server restart by reloading from disk.
	reloaded, err := New(dataFile, 48*time.Hour, WithClock(clock))
	if err != nil {
		t.Fatalf("reload store: %v", err)
	}
	snapAfterReload, err := reloaded.Feed()
	if err != nil {
		t.Fatalf("Feed after reload: %v", err)
	}
	if snapAfterReload.ETag != snapBeforeReload.ETag {
		t.Errorf("ETag changed across reload: before=%s after=%s", snapBeforeReload.ETag, snapAfterReload.ETag)
	}
	if snapAfterReload.Feed.UpdatedAt != snapBeforeReload.Feed.UpdatedAt {
		t.Errorf("UpdatedAt changed across reload: before=%s after=%s", snapBeforeReload.Feed.UpdatedAt, snapAfterReload.Feed.UpdatedAt)
	}

	if err := reloaded.Delete("pin-2"); err != nil {
		t.Fatalf("Delete pin-2: %v", err)
	}
	if err := reloaded.Delete("pin-2"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("second Delete err = %v, want ErrNotFound", err)
	}
}

func TestStore_RetentionExpirationUpdatesETag(t *testing.T) {
	dataFile := filepath.Join(t.TempDir(), "pins.json")
	current := time.Date(2026, 11, 25, 12, 0, 0, 0, time.UTC)
	clock := func() time.Time { return current }

	s, err := New(dataFile, 2*time.Hour, WithClock(clock))
	if err != nil {
		t.Fatalf("New store: %v", err)
	}

	_, err = s.Create(model.Pin{
		ID:              "short-lived",
		Title:           "Lunch",
		StartTime:       "2026-11-25T12:00:00Z",
		DurationMinutes: intPtr(60), // ends 13:00Z, expires after 15:00Z
		IconURI:         model.DefaultIconURI,
		LayoutType:      model.LayoutGenericPin,
	})
	if err != nil {
		t.Fatalf("Create: %v", err)
	}

	snap1, err := s.Feed()
	if err != nil {
		t.Fatal(err)
	}
	if len(snap1.Feed.Pins) != 1 {
		t.Fatalf("expected 1 active pin, got %d", len(snap1.Feed.Pins))
	}

	// Still active at 14:59Z (within 2h retention after 13:00Z end time).
	current = time.Date(2026, 11, 25, 14, 59, 0, 0, time.UTC)
	snap2, err := s.Feed()
	if err != nil {
		t.Fatal(err)
	}
	if snap2.ETag != snap1.ETag {
		t.Errorf("ETag changed before expiration: %s vs %s", snap1.ETag, snap2.ETag)
	}

	// Expired at 15:01Z.
	current = time.Date(2026, 11, 25, 15, 1, 0, 0, time.UTC)
	snap3, err := s.Feed()
	if err != nil {
		t.Fatal(err)
	}
	if len(snap3.Feed.Pins) != 0 {
		t.Fatalf("expected 0 active pins after expiry, got %d", len(snap3.Feed.Pins))
	}
	if snap3.ETag == snap1.ETag {
		t.Errorf("expected ETag to change after pin expiration")
	}
	if _, ok := s.Get("short-lived"); ok {
		t.Errorf("expected Get to hide expired pin")
	}
	// includeExpired=true still sees it until next write prunes it.
	if all := s.List(true); len(all.Pins) != 1 {
		t.Errorf("expected List(true) to include expired pin before prune, got %d", len(all.Pins))
	}
	// Subsequent poll at 15:05Z has stable ETag (does not jitter with wall clock).
	current = time.Date(2026, 11, 25, 15, 5, 0, 0, time.UTC)
	snap4, err := s.Feed()
	if err != nil {
		t.Fatal(err)
	}
	if snap4.ETag != snap3.ETag {
		t.Errorf("expected stable ETag across polls after expiry: %s vs %s", snap3.ETag, snap4.ETag)
	}
}

func TestStore_CorruptFileFailsFast(t *testing.T) {
	dataFile := filepath.Join(t.TempDir(), "pins.json")
	if err := os.WriteFile(dataFile, []byte("{not valid json"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := New(dataFile, 48*time.Hour); err == nil {
		t.Fatalf("expected error loading corrupt data file")
	}
}

func TestStore_LoadNormalizesLegacyUnsupportedLayoutTypes(t *testing.T) {
	dataFile := filepath.Join(t.TempDir(), "pins.json")
	legacyDoc := `{
		"schemaVersion": 1,
		"updatedAt": "2026-11-26T12:00:00Z",
		"pins": [
			{
				"id": "legacy-reminder",
				"title": "Legacy Reminder Pin",
				"startTime": "2026-11-27T12:00:00Z",
				"iconUri": "system://images/TIMELINE_CALENDAR",
				"layoutType": "GENERIC_REMINDER"
			},
			{
				"id": "legacy-notification",
				"title": "Legacy Notification Pin",
				"startTime": "2026-11-27T13:00:00Z",
				"iconUri": "system://images/TIMELINE_CALENDAR",
				"layoutType": "GENERIC_NOTIFICATION"
			},
			{
				"id": "calendar-pin",
				"title": "Calendar Pin",
				"startTime": "2026-11-27T14:00:00Z",
				"iconUri": "system://images/TIMELINE_CALENDAR",
				"layoutType": "CALENDAR_PIN"
			}
		]
	}`
	if err := os.WriteFile(dataFile, []byte(legacyDoc), 0o600); err != nil {
		t.Fatal(err)
	}

	now := time.Date(2026, 11, 26, 12, 0, 0, 0, time.UTC)
	s, err := New(dataFile, 48*time.Hour, WithClock(func() time.Time { return now }))
	if err != nil {
		t.Fatalf("New: %v", err)
	}

	remPin, ok := s.Get("legacy-reminder")
	if !ok || remPin.LayoutType != model.LayoutGenericPin {
		t.Fatalf("legacy-reminder LayoutType = %q (ok=%v), want %q", remPin.LayoutType, ok, model.LayoutGenericPin)
	}
	notifPin, ok := s.Get("legacy-notification")
	if !ok || notifPin.LayoutType != model.LayoutGenericPin {
		t.Fatalf("legacy-notification LayoutType = %q (ok=%v), want %q", notifPin.LayoutType, ok, model.LayoutGenericPin)
	}
	calPin, ok := s.Get("calendar-pin")
	if !ok || calPin.LayoutType != model.LayoutCalendarPin {
		t.Fatalf("calendar-pin LayoutType = %q (ok=%v), want %q", calPin.LayoutType, ok, model.LayoutCalendarPin)
	}
}

func TestStore_ConcurrentAccess(t *testing.T) {
	dataFile := filepath.Join(t.TempDir(), "pins.json")
	s, err := New(dataFile, 0)
	if err != nil {
		t.Fatal(err)
	}

	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		i := i
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < 5; j++ {
				id := fmt.Sprintf("pin-%d-%d", i, j)
				_, _, err := s.Upsert(model.Pin{
					ID:         id,
					Title:      "Concurrent",
					StartTime:  "2026-11-26T14:00:00Z",
					IconURI:    model.DefaultIconURI,
					LayoutType: model.LayoutGenericPin,
				})
				if err != nil {
					t.Errorf("Upsert %s: %v", id, err)
				}
				if _, err := s.Feed(); err != nil {
					t.Errorf("Feed: %v", err)
				}
			}
		}()
	}
	wg.Wait()

	snap, err := s.Feed()
	if err != nil {
		t.Fatal(err)
	}
	if len(snap.Feed.Pins) != 40 {
		t.Fatalf("expected 40 pins, got %d", len(snap.Feed.Pins))
	}
}
