package api

import (
	"crypto/sha256"
	"crypto/subtle"
	"net/http"
	"strings"
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

// Authenticate extracts a token from Authorization: Bearer <token> or X-API-Key and returns its Role.
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
