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
	"strings"
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
// Authentication uses SHA-256 token hashes; optional AES-256-GCM sealed tokens allow
// authenticated Web UI sessions to display QR codes and retrieve/copy API tokens.
// Raw plaintext tokens are never written to users.json.
type UserRecord struct {
	ID               string `json:"id"`
	Name             string `json:"name,omitempty"`
	ExternalSubject  string `json:"externalSubject,omitempty"`
	CreatedAt        string `json:"createdAt"`
	ReadTokenHash    string `json:"readTokenHash"`
	WriteTokenHash   string `json:"writeTokenHash"`
	ReadTokenSealed  string `json:"readTokenSealed,omitempty"`
	WriteTokenSealed string `json:"writeTokenSealed,omitempty"`
}

// UserSummary is the public/admin metadata view of a user without any token hashes or ciphertexts.
type UserSummary struct {
	ID              string `json:"id"`
	Name            string `json:"name,omitempty"`
	ExternalSubject string `json:"externalSubject,omitempty"`
	CreatedAt       string `json:"createdAt"`
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

// WithTokenEncryptionKey configures an explicit secret used to derive the AES-256-GCM key
// that seals per-user tokens at rest. When unset, UserManager persists a random 32-byte key
// in <dataDir>/.token-seal.key (mode 0600).
func WithTokenEncryptionKey(secret string) UserManagerOption {
	return func(m *UserManager) {
		if secret != "" {
			m.sealKey = deriveSealKeyFromSecret(secret)
			m.hasSealKey = true
			m.explicitSealKey = true
		}
	}
}

// UserManager persists registered users in <dataDir>/users.json and isolates each user's
// Timeline pins in <dataDir>/users/<userId>/pins.json.
type UserManager struct {
	mu              sync.RWMutex
	dataDir         string
	usersFile       string
	retention       time.Duration
	maxUsers        int
	now             func() time.Time
	sealKey         [32]byte
	hasSealKey      bool
	explicitSealKey bool

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

	if !m.explicitSealKey {
		key, ok, err := loadSealKeyFile(sealKeyPath(dataDir))
		if err != nil {
			return nil, err
		}
		if ok {
			m.sealKey = key
			m.hasSealKey = true
		}
	}

	if err := m.load(); err != nil {
		return nil, err
	}
	return m, nil
}

func (m *UserManager) ensureSealKeyLocked() ([32]byte, error) {
	if m.hasSealKey {
		return m.sealKey, nil
	}
	key, err := createSealKeyFile(m.dataDir, sealKeyPath(m.dataDir))
	if err != nil {
		return [32]byte{}, err
	}
	m.sealKey = key
	m.hasSealKey = true
	return key, nil
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

	seenSubjects := make(map[string]struct{})
	for _, rec := range doc.Users {
		if valErr := model.ValidateUserID(rec.ID); valErr != nil {
			return fmt.Errorf("invalid user id %q in %s: %s", rec.ID, m.usersFile, valErr.Message)
		}
		if _, dup := m.users[rec.ID]; dup {
			return fmt.Errorf("duplicate user id %q in %s", rec.ID, m.usersFile)
		}
		if rec.ExternalSubject != "" {
			normSub, valErr := model.ValidateExternalSubject(rec.ExternalSubject)
			if valErr != nil {
				return fmt.Errorf("invalid externalSubject for user %q in %s: %s", rec.ID, m.usersFile, valErr.Message)
			}
			if _, dup := seenSubjects[normSub]; dup {
				return fmt.Errorf("duplicate externalSubject %q in %s", normSub, m.usersFile)
			}
			seenSubjects[normSub] = struct{}{}
			rec.ExternalSubject = normSub
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

	return m.createUserLocked(cleanName, "")
}

// GetOrCreateUserBySubject returns the existing user linked to externalSubject (case-insensitive)
// or atomically provisions a new isolated user account when first seen.
func (m *UserManager) GetOrCreateUserBySubject(subject, displayName string) (UserSummary, bool, error) {
	normSubject, valErr := model.ValidateExternalSubject(subject)
	if valErr != nil {
		return UserSummary{}, false, model.ValidationErrors{*valErr}
	}

	cleanName, nameErr := model.ValidateUserName(displayName)
	if nameErr != nil || cleanName == "" {
		// Fall back cleanly to the trimmed subject (truncated to MaxUserNameLength if needed).
		fallback := strings.TrimSpace(subject)
		runes := []rune(fallback)
		if len(runes) > model.MaxUserNameLength {
			fallback = string(runes[:model.MaxUserNameLength])
		}
		if validFallback, err := model.ValidateUserName(fallback); err == nil {
			cleanName = validFallback
		}
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	for _, entry := range m.users {
		if entry.record.ExternalSubject == normSubject {
			return summaryFromRecord(entry.record), false, nil
		}
	}

	created, err := m.createUserLocked(cleanName, normSubject)
	if err != nil {
		return UserSummary{}, false, err
	}
	return created.User, true, nil
}

func (m *UserManager) createUserLocked(cleanName, externalSubject string) (CreatedUser, error) {
	if m.maxUsers > 0 && len(m.users) >= m.maxUsers {
		return CreatedUser{}, ErrUserLimitReached
	}

	sealKey, err := m.ensureSealKeyLocked()
	if err != nil {
		return CreatedUser{}, err
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

	readSealed, err := sealToken(sealKey, userID, "read", readToken)
	if err != nil {
		return CreatedUser{}, err
	}
	writeSealed, err := sealToken(sealKey, userID, "write", writeToken)
	if err != nil {
		return CreatedUser{}, err
	}

	readDigest := sha256.Sum256([]byte(readToken))
	writeDigest := sha256.Sum256([]byte(writeToken))

	rec := UserRecord{
		ID:               userID,
		Name:             cleanName,
		ExternalSubject:  externalSubject,
		CreatedAt:        m.now().UTC().Format(time.RFC3339),
		ReadTokenHash:    hex.EncodeToString(readDigest[:]),
		WriteTokenHash:   hex.EncodeToString(writeDigest[:]),
		ReadTokenSealed:  readSealed,
		WriteTokenSealed: writeSealed,
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
		User:       summaryFromRecord(rec),
		ReadToken:  readToken,
		WriteToken: writeToken,
	}, nil
}

// RevealTokens decrypts the stored sealed read and write tokens for userID.
// For legacy user records created before token sealing, available is false and err is nil.
func (m *UserManager) RevealTokens(userID string) (readToken string, writeToken string, available bool, err error) {
	m.mu.RLock()
	entry, ok := m.users[userID]
	hasKey := m.hasSealKey
	key := m.sealKey
	m.mu.RUnlock()

	if !ok {
		return "", "", false, ErrUserNotFound
	}
	if entry.record.ReadTokenSealed == "" || entry.record.WriteTokenSealed == "" || !hasKey {
		return "", "", false, nil
	}

	readToken, err = openToken(key, userID, "read", entry.record.ReadTokenSealed)
	if err != nil {
		return "", "", false, fmt.Errorf("reveal read token: %w", err)
	}
	writeToken, err = openToken(key, userID, "write", entry.record.WriteTokenSealed)
	if err != nil {
		return "", "", false, fmt.Errorf("reveal write token: %w", err)
	}
	return readToken, writeToken, true, nil
}

// RotateTokens generates new read and/or write tokens for userID, updates their hashes and
// sealed ciphertexts atomically, and returns the resulting active (readToken, writeToken) pair.
// If a token was not previously sealed (legacy record), it is also regenerated so both tokens
// are recoverable from the authenticated UI.
func (m *UserManager) RotateTokens(userID string, rotateRead, rotateWrite bool) (readToken string, writeToken string, err error) {
	if !rotateRead && !rotateWrite {
		return "", "", errors.New("at least one of rotateRead or rotateWrite must be true")
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	entry, ok := m.users[userID]
	if !ok {
		return "", "", ErrUserNotFound
	}

	sealKey, err := m.ensureSealKeyLocked()
	if err != nil {
		return "", "", err
	}

	rec := entry.record
	readDigest := entry.readDigest
	writeDigest := entry.writeDigest

	if !rotateRead && rec.ReadTokenSealed != "" {
		existingRead, openErr := openToken(sealKey, userID, "read", rec.ReadTokenSealed)
		if openErr == nil {
			readToken = existingRead
		} else {
			rotateRead = true
		}
	} else {
		rotateRead = true
	}

	if !rotateWrite && rec.WriteTokenSealed != "" {
		existingWrite, openErr := openToken(sealKey, userID, "write", rec.WriteTokenSealed)
		if openErr == nil {
			writeToken = existingWrite
		} else {
			rotateWrite = true
		}
	} else {
		rotateWrite = true
	}

	if rotateRead {
		readToken, err = model.GenerateToken()
		if err != nil {
			return "", "", err
		}
		readSealed, err := sealToken(sealKey, userID, "read", readToken)
		if err != nil {
			return "", "", err
		}
		readDigest = sha256.Sum256([]byte(readToken))
		rec.ReadTokenHash = hex.EncodeToString(readDigest[:])
		rec.ReadTokenSealed = readSealed
	}

	if rotateWrite {
		writeToken, err = model.GenerateToken()
		if err != nil {
			return "", "", err
		}
		writeSealed, err := sealToken(sealKey, userID, "write", writeToken)
		if err != nil {
			return "", "", err
		}
		writeDigest = sha256.Sum256([]byte(writeToken))
		rec.WriteTokenHash = hex.EncodeToString(writeDigest[:])
		rec.WriteTokenSealed = writeSealed
	}

	next := make(map[string]userEntry, len(m.users))
	for k, v := range m.users {
		next[k] = v
	}
	next[userID] = userEntry{
		record:      rec,
		readDigest:  readDigest,
		writeDigest: writeDigest,
	}

	if err := m.persistLocked(next); err != nil {
		return "", "", err
	}
	m.users = next
	return readToken, writeToken, nil
}

// UpdateUserName updates the friendly display name for userID.
func (m *UserManager) UpdateUserName(userID, name string) (UserSummary, error) {
	cleanName, valErr := model.ValidateUserName(name)
	if valErr != nil {
		return UserSummary{}, model.ValidationErrors{*valErr}
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	entry, ok := m.users[userID]
	if !ok {
		return UserSummary{}, ErrUserNotFound
	}
	rec := entry.record
	rec.Name = cleanName

	next := make(map[string]userEntry, len(m.users))
	for k, v := range m.users {
		next[k] = v
	}
	next[userID] = userEntry{
		record:      rec,
		readDigest:  entry.readDigest,
		writeDigest: entry.writeDigest,
	}

	if err := m.persistLocked(next); err != nil {
		return UserSummary{}, err
	}
	m.users = next
	return summaryFromRecord(rec), nil
}

// FindUserByWriteToken scans all registered users in constant time without short-circuiting
// and returns the user matching tokenDigest's write token hash.
func (m *UserManager) FindUserByWriteToken(tokenDigest [32]byte) (UserSummary, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	var matched UserSummary
	found := false
	for _, entry := range m.users {
		if subtle.ConstantTimeCompare(tokenDigest[:], entry.writeDigest[:]) == 1 {
			matched = summaryFromRecord(entry.record)
			found = true
		}
	}
	return matched, found
}

func summaryFromRecord(rec UserRecord) UserSummary {
	return UserSummary{
		ID:              rec.ID,
		Name:            rec.Name,
		ExternalSubject: rec.ExternalSubject,
		CreatedAt:       rec.CreatedAt,
	}
}

// GetUser returns metadata for a registered user by ID.
func (m *UserManager) GetUser(userID string) (UserSummary, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	entry, ok := m.users[userID]
	if !ok {
		return UserSummary{}, false
	}
	return summaryFromRecord(entry.record), true
}

// ListUsers returns all registered users in deterministic order without token hashes.
func (m *UserManager) ListUsers() []UserSummary {
	m.mu.RLock()
	defer m.mu.RUnlock()

	out := make([]UserSummary, 0, len(m.users))
	for _, entry := range m.users {
		out = append(out, summaryFromRecord(entry.record))
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
