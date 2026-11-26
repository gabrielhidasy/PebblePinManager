package model

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"regexp"
	"strings"
	"unicode/utf8"
)

const (
	MaxUserIDLength          = 64
	MaxUserNameLength        = 80
	MaxExternalSubjectLength = 128
)

var (
	validUserIDRegex = regexp.MustCompile(`^[a-z0-9][a-z0-9_-]*$`)

	reservedUserIDs = map[string]struct{}{
		"api":       {},
		"assets":    {},
		"healthz":   {},
		"pins.json": {},
		"register":  {},
		"static":    {},
		"ui":        {},
		"users":     {},
	}
)

// GenerateUserID generates a cryptographically random 128-bit user identifier (32 lowercase hex chars).
func GenerateUserID() (string, error) {
	var buf [16]byte
	if _, err := rand.Read(buf[:]); err != nil {
		return "", fmt.Errorf("generate user id: %w", err)
	}
	return hex.EncodeToString(buf[:]), nil
}

// GenerateToken generates a cryptographically random 256-bit bearer token (64 lowercase hex chars).
func GenerateToken() (string, error) {
	var buf [32]byte
	if _, err := rand.Read(buf[:]); err != nil {
		return "", fmt.Errorf("generate token: %w", err)
	}
	return hex.EncodeToString(buf[:]), nil
}

// ValidateUserID checks that a user ID is safe for URL paths and filesystem directories.
func ValidateUserID(id string) *ValidationError {
	if strings.TrimSpace(id) == "" {
		return &ValidationError{
			Field:   "userId",
			Code:    "user_id_blank",
			Message: "User ID must not be blank.",
		}
	}
	if id != strings.TrimSpace(id) {
		return &ValidationError{
			Field:   "userId",
			Code:    "user_id_invalid",
			Message: "User ID must not contain leading or trailing whitespace.",
		}
	}
	if n := utf8.RuneCountInString(id); n > MaxUserIDLength {
		return &ValidationError{
			Field:   "userId",
			Code:    "user_id_too_long",
			Message: fmt.Sprintf("User ID is too long (%d/%d characters).", n, MaxUserIDLength),
		}
	}
	if !validUserIDRegex.MatchString(id) {
		return &ValidationError{
			Field:   "userId",
			Code:    "user_id_invalid",
			Message: "User ID must start with a lowercase letter or digit and may only contain lowercase letters, digits, '-' and '_'.",
		}
	}
	if _, reserved := reservedUserIDs[id]; reserved {
		return &ValidationError{
			Field:   "userId",
			Code:    "user_id_reserved",
			Message: fmt.Sprintf("User ID %q is reserved.", id),
		}
	}
	return nil
}

// ValidateUserName trims and validates an optional friendly user/device display name.
func ValidateUserName(name string) (string, *ValidationError) {
	trimmed := strings.TrimSpace(name)
	if trimmed == "" {
		return "", nil
	}
	if n := utf8.RuneCountInString(trimmed); n > MaxUserNameLength {
		return "", &ValidationError{
			Field:   "name",
			Code:    "user_name_too_long",
			Message: fmt.Sprintf("User name is too long (%d/%d characters).", n, MaxUserNameLength),
		}
	}
	for _, r := range trimmed {
		if r < 0x20 || r == 0x7f {
			return "", &ValidationError{
				Field:   "name",
				Code:    "user_name_invalid",
				Message: "User name must not contain control characters.",
			}
		}
	}
	return trimmed, nil
}

// ValidateExternalSubject trims, validates, and normalizes an external proxy identity
// (such as Authelia's Remote-User header) for mapping to a persistent multi-user feed.
func ValidateExternalSubject(subject string) (string, *ValidationError) {
	trimmed := strings.TrimSpace(subject)
	if trimmed == "" {
		return "", &ValidationError{
			Field:   "subject",
			Code:    "subject_blank",
			Message: "External identity subject must not be blank.",
		}
	}
	if n := utf8.RuneCountInString(trimmed); n > MaxExternalSubjectLength {
		return "", &ValidationError{
			Field:   "subject",
			Code:    "subject_too_long",
			Message: fmt.Sprintf("External identity subject is too long (%d/%d characters).", n, MaxExternalSubjectLength),
		}
	}
	for _, r := range trimmed {
		if r < 0x20 || r == 0x7f {
			return "", &ValidationError{
				Field:   "subject",
				Code:    "subject_invalid",
				Message: "External identity subject must not contain control characters.",
			}
		}
	}
	return strings.ToLower(trimmed), nil
}
