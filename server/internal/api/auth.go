package api

import (
	"crypto/sha256"
	"crypto/subtle"
	"net/http"
	"strings"

	"github.com/gabrielhidasy/PebblePinManager/server/internal/store"
)

// Role represents the authorization level granted to a request.
type Role int

const (
	RoleNone Role = iota
	RoleRead
	RoleWrite
)

func (r Role) String() string {
	switch r {
	case RoleRead:
		return "read"
	case RoleWrite:
		return "write"
	default:
		return "none"
	}
}

// Authenticator performs constant-time verification against hashed bearer / API key tokens.
// Plaintext tokens are never retained after construction.
type Authenticator struct {
	hasWrite    bool
	writeDigest [32]byte
	hasRead     bool
	readDigest  [32]byte
}

// NewAuthenticator creates an Authenticator from optional read-write and read-only tokens.
func NewAuthenticator(writeToken, readToken string) *Authenticator {
	a := &Authenticator{}
	if writeToken != "" {
		a.hasWrite = true
		a.writeDigest = sha256.Sum256([]byte(writeToken))
	}
	if readToken != "" {
		a.hasRead = true
		a.readDigest = sha256.Sum256([]byte(readToken))
	}
	return a
}

// Authenticate extracts a token from Authorization: Bearer <token> or X-API-Key and returns its global Role.
func (a *Authenticator) Authenticate(r *http.Request) Role {
	token, ok := extractToken(r)
	if !ok {
		return RoleNone
	}

	presented := sha256.Sum256([]byte(token))

	// Always compare both digests without short-circuiting to avoid timing differences.
	writeMatch := subtle.ConstantTimeCompare(presented[:], a.writeDigest[:]) == 1 && a.hasWrite
	readMatch := subtle.ConstantTimeCompare(presented[:], a.readDigest[:]) == 1 && a.hasRead

	if writeMatch {
		return RoleWrite
	}
	if readMatch {
		return RoleRead
	}
	return RoleNone
}

type userAuthResult struct {
	role         Role
	isAdmin      bool
	isGlobalRead bool
	userExists   bool
}

// AuthenticateForUser authenticates a request against global admin/read tokens and the target user's scoped tokens.
// All digest comparisons execute in constant time without short-circuiting to prevent timing or user-enumeration leaks.
func (a *Authenticator) AuthenticateForUser(r *http.Request, userID string, users *store.UserManager) userAuthResult {
	token, ok := extractToken(r)
	if !ok {
		return userAuthResult{}
	}

	presented := sha256.Sum256([]byte(token))

	globalWrite := subtle.ConstantTimeCompare(presented[:], a.writeDigest[:]) == 1 && a.hasWrite
	globalRead := subtle.ConstantTimeCompare(presented[:], a.readDigest[:]) == 1 && a.hasRead

	var userWrite, userRead, exists bool
	if users != nil {
		userWrite, userRead, exists = users.AuthenticateUser(userID, presented)
	}

	if globalWrite {
		return userAuthResult{
			role:       RoleWrite,
			isAdmin:    true,
			userExists: exists,
		}
	}
	if userWrite {
		return userAuthResult{
			role:       RoleWrite,
			userExists: true,
		}
	}
	if userRead {
		return userAuthResult{
			role:       RoleRead,
			userExists: true,
		}
	}
	if globalRead {
		return userAuthResult{
			role:         RoleNone,
			isGlobalRead: true,
			userExists:   exists,
		}
	}
	return userAuthResult{
		role:       RoleNone,
		userExists: exists,
	}
}

func extractToken(r *http.Request) (string, bool) {
	if authHeader := strings.TrimSpace(r.Header.Get("Authorization")); authHeader != "" {
		parts := strings.Fields(authHeader)
		if len(parts) == 2 && strings.EqualFold(parts[0], "Bearer") && parts[1] != "" {
			return parts[1], true
		}
		// An explicit malformed Authorization header must not fall through to another header.
		return "", false
	}

	if apiKey := strings.TrimSpace(r.Header.Get("X-API-Key")); apiKey != "" {
		return apiKey, true
	}

	return "", false
}
