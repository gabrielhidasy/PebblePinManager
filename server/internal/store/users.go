package store

import (
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
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

const usersSchemaVersion = 1

var (
	ErrUserNotFound     = errors.New("user not found")
	ErrUserLimitReached = errors.New("maximum number of registered users reached")
)

// UserRecord is the durable representation of a registered user persisted in users.json.
// Only SHA-256 token hashes are stored; raw tokens are never written to disk.
type UserRecord struct {
	ID             string `json:"id"`
	Name           string `json:"name,omitempty"`
	CreatedAt      string `json:"createdAt"`
	ReadTokenHash  string `json:"readTokenHash"`
	WriteTokenHash string `json:"writeTokenHash"`
}

// UserSummary is the public/admin metadata view of a user without any token hashes.
type UserSummary struct {
	ID        string `json:"id"`
	Name      string `json:"name,omitempty"`
	CreatedAt string `json:"createdAt"`
}

// CreatedUser contains the newly created user metadata and one-time plaintext read/write tokens.
type CreatedUser struct {
	User       UserSummary
	ReadToken  string
	WriteToken string
}

type usersDocument struct {
	SchemaVersion int          `json:"schemaVersion"`
	Users         []UserRecord `json:"users"`
}

type userEntry struct {
	record      UserRecord
	readDigest  [32]byte
	writeDigest [32]byte
}

// UserManagerOption configures a UserManager instance.
type UserManagerOption func(*UserManager)

// WithUserClock overrides the clock used for user timestamps and per-user pin stores.
func WithUserClock(now func() time.Time) UserManagerOption {
	return func(m *UserManager) {
		if now != nil {
			m.now = now
		}
	}
}

// WithMaxUsers sets the maximum number of registered users allowed (0 disables the limit).
func WithMaxUsers(limit int) UserManagerOption {
	return func(m *UserManager) {
		if limit >= 0 {
			m.maxUsers = limit
		}
	}
}

// UserManager persists registered users in <dataDir>/users.json and isolates each user's
// Timeline pins in <dataDir>/users/<userId>/pins.json.
type UserManager struct {
	mu        sync.RWMutex
	dataDir   string
	usersFile string
	retention time.Duration
	maxUsers  int
	now       func() time.Time

	users  map[string]userEntry
	stores map[string]*Store
}

// NewUserManager initializes a UserManager rooted at dataDir and loads existing users from users.json.
func NewUserManager(dataDir string, retention time.Duration, opts ...UserManagerOption) (*UserManager, error) {
	if dataDir == "" {
		return nil, errors.New("dataDir must not be empty")
	}
	if retention < 0 {
		return nil, fmt.Errorf("retention must be >= 0 (got %s)", retention)
	}

	m := &UserManager{
		dataDir:   dataDir,
		usersFile: filepath.Join(dataDir, "users.json"),
		retention: retention,
		now:       time.Now,
		users:     make(map[string]userEntry),
		stores:    make(map[string]*Store),
	}
	for _, opt := range opts {
		opt(m)
	}

	if err := m.load(); err != nil {
		return nil, err
	}
	return m, nil
}

func (m *UserManager) load() error {
	raw, err := os.ReadFile(m.usersFile)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil
		}
		return fmt.Errorf("read users file %s: %w", m.usersFile, err)
	}

	var doc usersDocument
	if err := json.Unmarshal(raw, &doc); err != nil {
		return fmt.Errorf("parse users file %s: %w", m.usersFile, err)
	}
	if doc.SchemaVersion != 0 && doc.SchemaVersion != usersSchemaVersion {
		return fmt.Errorf("unsupported users schemaVersion %d in %s (expected %d)", doc.SchemaVersion, m.usersFile, usersSchemaVersion)
	}

	for _, rec := range doc.Users {
		if valErr := model.ValidateUserID(rec.ID); valErr != nil {
			return fmt.Errorf("invalid user id %q in %s: %s", rec.ID, m.usersFile, valErr.Message)
		}
		if _, dup := m.users[rec.ID]; dup {
			return fmt.Errorf("duplicate user id %q in %s", rec.ID, m.usersFile)
		}
		readDigest, err := decodeSHA256Hex(rec.ReadTokenHash)
		if err != nil {
			return fmt.Errorf("invalid readTokenHash for user %q in %s: %w", rec.ID, m.usersFile, err)
		}
		writeDigest, err := decodeSHA256Hex(rec.WriteTokenHash)
		if err != nil {
			return fmt.Errorf("invalid writeTokenHash for user %q in %s: %w", rec.ID, m.usersFile, err)
		}
		m.users[rec.ID] = userEntry{
			record:      rec,
			readDigest:  readDigest,
			writeDigest: writeDigest,
		}
	}
	return nil
}

// CreateUser registers a new user with a cryptographically random ID and scoped read/write tokens.
func (m *UserManager) CreateUser(name string) (CreatedUser, error) {
	cleanName, valErr := model.ValidateUserName(name)
	if valErr != nil {
		return CreatedUser{}, model.ValidationErrors{*valErr}
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	if m.maxUsers > 0 && len(m.users) >= m.maxUsers {
		return CreatedUser{}, ErrUserLimitReached
	}

	var userID string
	for attempt := 0; attempt < 8; attempt++ {
		candidate, err := model.GenerateUserID()
		if err != nil {
			return CreatedUser{}, err
		}
		if _, exists := m.users[candidate]; !exists {
			userID = candidate
			break
		}
	}
	if userID == "" {
		return CreatedUser{}, errors.New("failed to allocate unique user id")
	}

	readToken, err := model.GenerateToken()
	if err != nil {
		return CreatedUser{}, err
	}
	writeToken, err := model.GenerateToken()
	if err != nil {
		return CreatedUser{}, err
	}

	readDigest := sha256.Sum256([]byte(readToken))
	writeDigest := sha256.Sum256([]byte(writeToken))

	rec := UserRecord{
		ID:             userID,
		Name:           cleanName,
		CreatedAt:      m.now().UTC().Format(time.RFC3339),
		ReadTokenHash:  hex.EncodeToString(readDigest[:]),
		WriteTokenHash: hex.EncodeToString(writeDigest[:]),
	}

	next := make(map[string]userEntry, len(m.users)+1)
	for k, v := range m.users {
		next[k] = v
	}
	next[userID] = userEntry{
		record:      rec,
		readDigest:  readDigest,
		writeDigest: writeDigest,
	}

	if err := m.persistLocked(next); err != nil {
		return CreatedUser{}, err
	}
	m.users = next

	return CreatedUser{
		User: UserSummary{
			ID:        rec.ID,
			Name:      rec.Name,
			CreatedAt: rec.CreatedAt,
		},
		ReadToken:  readToken,
		WriteToken: writeToken,
	}, nil
}

// GetUser returns metadata for a registered user by ID.
func (m *UserManager) GetUser(userID string) (UserSummary, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	entry, ok := m.users[userID]
	if !ok {
		return UserSummary{}, false
	}
	return UserSummary{
		ID:        entry.record.ID,
		Name:      entry.record.Name,
		CreatedAt: entry.record.CreatedAt,
	}, true
}

// ListUsers returns all registered users in deterministic order without token hashes.
func (m *UserManager) ListUsers() []UserSummary {
	m.mu.RLock()
	defer m.mu.RUnlock()

	out := make([]UserSummary, 0, len(m.users))
	for _, entry := range m.users {
		out = append(out, UserSummary{
			ID:        entry.record.ID,
			Name:      entry.record.Name,
			CreatedAt: entry.record.CreatedAt,
		})
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].CreatedAt != out[j].CreatedAt {
			return out[i].CreatedAt < out[j].CreatedAt
		}
		return out[i].ID < out[j].ID
	})
	return out
}

// DeleteUser removes a registered user and deletes their isolated pin store directory.
func (m *UserManager) DeleteUser(userID string) error {
	if valErr := model.ValidateUserID(userID); valErr != nil {
		return ErrUserNotFound
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	if _, exists := m.users[userID]; !exists {
		return ErrUserNotFound
	}

	next := make(map[string]userEntry, len(m.users)-1)
	for k, v := range m.users {
		if k != userID {
			next[k] = v
		}
	}

	if err := m.persistLocked(next); err != nil {
		return err
	}
	m.users = next
	delete(m.stores, userID)

	userDir := filepath.Join(m.dataDir, "users", userID)
	if err := os.RemoveAll(userDir); err != nil {
		return fmt.Errorf("remove user data directory %s: %w", userDir, err)
	}
	return nil
}

// PinStore returns the isolated pin Store for a registered user, lazily initializing it on first access.
func (m *UserManager) PinStore(userID string) (*Store, error) {
	if valErr := model.ValidateUserID(userID); valErr != nil {
		return nil, ErrUserNotFound
	}

	m.mu.RLock()
	_, exists := m.users[userID]
	st := m.stores[userID]
	m.mu.RUnlock()

	if !exists {
		return nil, ErrUserNotFound
	}
	if st != nil {
		return st, nil
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	if _, exists := m.users[userID]; !exists {
		return nil, ErrUserNotFound
	}
	if st := m.stores[userID]; st != nil {
		return st, nil
	}

	pinFile := filepath.Join(m.dataDir, "users", userID, "pins.json")
	created, err := New(pinFile, m.retention, WithClock(m.now))
	if err != nil {
		return nil, err
	}
	m.stores[userID] = created
	return created, nil
}

// AuthenticateUser checks tokenDigest against userID's write and read token hashes in constant time.
// Even when userID does not exist, constant-time comparisons are still executed to avoid timing leaks.
func (m *UserManager) AuthenticateUser(userID string, tokenDigest [32]byte) (matchedWrite bool, matchedRead bool, exists bool) {
	m.mu.RLock()
	entry, ok := m.users[userID]
	m.mu.RUnlock()

	writeCmp := subtle.ConstantTimeCompare(tokenDigest[:], entry.writeDigest[:]) == 1
	readCmp := subtle.ConstantTimeCompare(tokenDigest[:], entry.readDigest[:]) == 1
	return ok && writeCmp, ok && readCmp, ok
}

func (m *UserManager) persistLocked(usersMap map[string]userEntry) error {
	records := make([]UserRecord, 0, len(usersMap))
	for _, entry := range usersMap {
		records = append(records, entry.record)
	}
	sort.Slice(records, func(i, j int) bool {
		if records[i].CreatedAt != records[j].CreatedAt {
			return records[i].CreatedAt < records[j].CreatedAt
		}
		return records[i].ID < records[j].ID
	})

	doc := usersDocument{
		SchemaVersion: usersSchemaVersion,
		Users:         records,
	}

	encoded, err := json.MarshalIndent(doc, "", "  ")
	if err != nil {
		return fmt.Errorf("encode users json: %w", err)
	}
	encoded = append(encoded, '\n')

	if err := os.MkdirAll(m.dataDir, 0o700); err != nil {
		return fmt.Errorf("create data directory %s: %w", m.dataDir, err)
	}

	tmp, err := os.CreateTemp(m.dataDir, ".users-*.tmp")
	if err != nil {
		return fmt.Errorf("create temp users file: %w", err)
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
		return fmt.Errorf("chmod temp users file: %w", err)
	}
	if _, err := tmp.Write(encoded); err != nil {
		_ = tmp.Close()
		return fmt.Errorf("write temp users file: %w", err)
	}
	if err := tmp.Sync(); err != nil {
		_ = tmp.Close()
		return fmt.Errorf("sync temp users file: %w", err)
	}
	if err := tmp.Close(); err != nil {
		return fmt.Errorf("close temp users file: %w", err)
	}

	if err := os.Rename(tmpPath, m.usersFile); err != nil {
		return fmt.Errorf("replace users file %s: %w", m.usersFile, err)
	}
	cleanup = false

	if dirFile, err := os.Open(m.dataDir); err == nil {
		_ = dirFile.Sync()
		_ = dirFile.Close()
	}

	return nil
}

func decodeSHA256Hex(raw string) ([32]byte, error) {
	var out [32]byte
	decoded, err := hex.DecodeString(raw)
	if err != nil {
		return out, err
	}
	if len(decoded) != 32 {
		return out, fmt.Errorf("expected 32-byte digest, got %d bytes", len(decoded))
	}
	copy(out[:], decoded)
	return out, nil
}
