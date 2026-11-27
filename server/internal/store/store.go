package store

import (
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"

	"github.com/gabrielhidasy/PebblePinManager/server/internal/model"
)

var (
	ErrNotFound      = errors.New("pin not found")
	ErrAlreadyExists = errors.New("pin already exists")
)

// FeedSnapshot contains a deterministically serialized feed together with its caching metadata.
type FeedSnapshot struct {
	Feed         model.PinFeed
	JSON         []byte
	ETag         string
	LastModified time.Time
}

// Option configures a Store instance.
type Option func(*Store)

// WithClock overrides the clock used for retention checks and mutation timestamps (useful in tests).
func WithClock(now func() time.Time) Option {
	return func(s *Store) {
		if now != nil {
			s.now = now
		}
	}
}

// Store persists Pebble Timeline pins in an atomic JSON file.
type Store struct {
	mu        sync.RWMutex
	dataFile  string
	retention time.Duration
	now       func() time.Time

	updatedAt time.Time
	pins      map[string]model.Pin
}

// New initializes a Store backed by dataFile, loading existing pins if the file is present.
func New(dataFile string, retention time.Duration, opts ...Option) (*Store, error) {
	if dataFile == "" {
		return nil, errors.New("dataFile must not be empty")
	}
	if retention < 0 {
		return nil, fmt.Errorf("retention must be >= 0 (got %s)", retention)
	}

	s := &Store{
		dataFile:  dataFile,
		retention: retention,
		now:       time.Now,
		updatedAt: time.Unix(0, 0).UTC(),
		pins:      make(map[string]model.Pin),
	}
	for _, opt := range opts {
		opt(s)
	}

	if err := s.load(); err != nil {
		return nil, err
	}
	return s, nil
}

func (s *Store) load() error {
	raw, err := os.ReadFile(s.dataFile)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil
		}
		return fmt.Errorf("read data file %s: %w", s.dataFile, err)
	}

	var doc model.PinFeed
	if err := json.Unmarshal(raw, &doc); err != nil {
		return fmt.Errorf("parse data file %s: %w", s.dataFile, err)
	}
	if doc.SchemaVersion != 0 && doc.SchemaVersion != model.SchemaVersion {
		return fmt.Errorf("unsupported schemaVersion %d in %s (expected %d)", doc.SchemaVersion, s.dataFile, model.SchemaVersion)
	}
	if doc.UpdatedAt != "" {
		ts, err := model.ParseTimestamp(doc.UpdatedAt)
		if err != nil {
			return fmt.Errorf("invalid updatedAt %q in %s: %w", doc.UpdatedAt, s.dataFile, err)
		}
		s.updatedAt = ts.UTC()
	}

	for _, pin := range doc.Pins {
		if pin.ID == "" {
			return fmt.Errorf("corrupt pin with blank id in %s", s.dataFile)
		}
		if !model.IsValidLayoutType(pin.LayoutType) {
			pin.LayoutType = model.DefaultLayoutType
		}
		s.pins[pin.ID] = clonePin(pin)
	}
	return nil
}

// Feed returns a snapshot of all non-expired pins along with canonical JSON, ETag, and Last-Modified.
func (s *Store) Feed() (FeedSnapshot, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	now := s.now().UTC()
	active, effectiveUpdatedAt := s.activePinsLocked(now)

	feed := model.PinFeed{
		SchemaVersion: model.SchemaVersion,
		UpdatedAt:     effectiveUpdatedAt.UTC().Format(time.RFC3339),
		Pins:          active,
	}

	payload, err := json.Marshal(feed)
	if err != nil {
		return FeedSnapshot{}, fmt.Errorf("marshal pin feed: %w", err)
	}
	payload = append(payload, '\n')

	sum := sha256.Sum256(payload)
	etag := fmt.Sprintf("\"%x\"", sum[:16])

	return FeedSnapshot{
		Feed:         feed,
		JSON:         payload,
		ETag:         etag,
		LastModified: effectiveUpdatedAt.UTC().Truncate(time.Second),
	}, nil
}

// List returns pins in deterministic order. When includeExpired is false, expired pins are filtered out.
func (s *Store) List(includeExpired bool) model.PinFeed {
	s.mu.RLock()
	defer s.mu.RUnlock()

	now := s.now().UTC()
	if !includeExpired {
		active, effectiveUpdatedAt := s.activePinsLocked(now)
		return model.PinFeed{
			SchemaVersion: model.SchemaVersion,
			UpdatedAt:     effectiveUpdatedAt.UTC().Format(time.RFC3339),
			Pins:          active,
		}
	}

	all := make([]model.Pin, 0, len(s.pins))
	for _, p := range s.pins {
		all = append(all, clonePin(p))
	}
	sortPins(all)
	return model.PinFeed{
		SchemaVersion: model.SchemaVersion,
		UpdatedAt:     s.updatedAt.UTC().Format(time.RFC3339),
		Pins:          all,
	}
}

// Get returns a single non-expired pin by ID.
func (s *Store) Get(id string) (model.Pin, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	pin, ok := s.pins[id]
	if !ok {
		return model.Pin{}, false
	}
	if s.isExpired(pin, s.now().UTC()) {
		return model.Pin{}, false
	}
	return clonePin(pin), true
}

// Create stores a new pin, returning ErrAlreadyExists if an active pin with the same ID already exists.
func (s *Store) Create(pin model.Pin) (model.Pin, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	now := s.now().UTC()
	next := s.prunedCopyLocked(now)
	if _, exists := next[pin.ID]; exists {
		return model.Pin{}, ErrAlreadyExists
	}

	stored := clonePin(pin)
	next[stored.ID] = stored

	if err := s.persistLocked(next, now); err != nil {
		return model.Pin{}, err
	}
	s.pins = next
	s.updatedAt = now
	return clonePin(stored), nil
}

// Upsert inserts or replaces a pin by ID, returning whether a new pin was created.
func (s *Store) Upsert(pin model.Pin) (model.Pin, bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	now := s.now().UTC()
	next := s.prunedCopyLocked(now)
	_, existed := next[pin.ID]

	stored := clonePin(pin)
	next[stored.ID] = stored

	if err := s.persistLocked(next, now); err != nil {
		return model.Pin{}, false, err
	}
	s.pins = next
	s.updatedAt = now
	return clonePin(stored), !existed, nil
}

// Delete removes a pin by ID, returning ErrNotFound if no active pin matches.
func (s *Store) Delete(id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	now := s.now().UTC()
	next := s.prunedCopyLocked(now)
	if _, exists := next[id]; !exists {
		return ErrNotFound
	}
	delete(next, id)

	if err := s.persistLocked(next, now); err != nil {
		return err
	}
	s.pins = next
	s.updatedAt = now
	return nil
}

func (s *Store) activePinsLocked(now time.Time) ([]model.Pin, time.Time) {
	active := make([]model.Pin, 0, len(s.pins))
	effectiveUpdatedAt := s.updatedAt

	for _, pin := range s.pins {
		expired, expiryAt := s.expiryStatus(pin, now)
		if expired {
			if expiryAt.After(effectiveUpdatedAt) {
				effectiveUpdatedAt = expiryAt
			}
			continue
		}
		active = append(active, clonePin(pin))
	}

	sortPins(active)
	return active, effectiveUpdatedAt.UTC()
}

func (s *Store) prunedCopyLocked(now time.Time) map[string]model.Pin {
	next := make(map[string]model.Pin, len(s.pins)+1)
	for id, pin := range s.pins {
		if s.isExpired(pin, now) {
			continue
		}
		next[id] = clonePin(pin)
	}
	return next
}

func (s *Store) isExpired(pin model.Pin, now time.Time) bool {
	expired, _ := s.expiryStatus(pin, now)
	return expired
}

func (s *Store) expiryStatus(pin model.Pin, now time.Time) (bool, time.Time) {
	if s.retention <= 0 {
		return false, time.Time{}
	}
	endTime, err := pin.EndTime()
	if err != nil {
		return false, time.Time{}
	}
	expiryAt := endTime.Add(s.retention).UTC()
	return expiryAt.Before(now), expiryAt
}

func (s *Store) persistLocked(pinsMap map[string]model.Pin, updatedAt time.Time) error {
	pins := make([]model.Pin, 0, len(pinsMap))
	for _, p := range pinsMap {
		pins = append(pins, clonePin(p))
	}
	sortPins(pins)

	doc := model.PinFeed{
		SchemaVersion: model.SchemaVersion,
		UpdatedAt:     updatedAt.UTC().Format(time.RFC3339),
		Pins:          pins,
	}

	encoded, err := json.MarshalIndent(doc, "", "  ")
	if err != nil {
		return fmt.Errorf("encode state json: %w", err)
	}
	encoded = append(encoded, '\n')

	dir := filepath.Dir(s.dataFile)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return fmt.Errorf("create data directory %s: %w", dir, err)
	}

	tmp, err := os.CreateTemp(dir, ".pins-*.tmp")
	if err != nil {
		return fmt.Errorf("create temp state file: %w", err)
	}
	tmpPath := tmp.Name()
	cleanup := true
	defer func() {
		if cleanup {
			_ = os.Remove(tmpPath)
		}
	}()

	if err := tmp.Chmod(0o600); err != nil {
		_ = tmp.Close()
		return fmt.Errorf("chmod temp state file: %w", err)
	}
	if _, err := tmp.Write(encoded); err != nil {
		_ = tmp.Close()
		return fmt.Errorf("write temp state file: %w", err)
	}
	if err := tmp.Sync(); err != nil {
		_ = tmp.Close()
		return fmt.Errorf("sync temp state file: %w", err)
	}
	if err := tmp.Close(); err != nil {
		return fmt.Errorf("close temp state file: %w", err)
	}

	if err := os.Rename(tmpPath, s.dataFile); err != nil {
		return fmt.Errorf("replace state file %s: %w", s.dataFile, err)
	}
	cleanup = false

	// Best-effort directory fsync for crash durability.
	if dirFile, err := os.Open(dir); err == nil {
		_ = dirFile.Sync()
		_ = dirFile.Close()
	}

	return nil
}

func sortPins(pins []model.Pin) {
	sort.Slice(pins, func(i, j int) bool {
		if pins[i].StartTime != pins[j].StartTime {
			return pins[i].StartTime < pins[j].StartTime
		}
		return pins[i].ID < pins[j].ID
	})
}

func clonePin(p model.Pin) model.Pin {
	out := p
	if p.Description != nil {
		v := *p.Description
		out.Description = &v
	}
	if p.DurationMinutes != nil {
		v := *p.DurationMinutes
		out.DurationMinutes = &v
	}
	if p.BackgroundColor != nil {
		v := *p.BackgroundColor
		out.BackgroundColor = &v
	}
	if p.PrimaryColor != nil {
		v := *p.PrimaryColor
		out.PrimaryColor = &v
	}
	if p.SecondaryColor != nil {
		v := *p.SecondaryColor
		out.SecondaryColor = &v
	}
	return out
}
