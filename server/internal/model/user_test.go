package model

import (
	"strings"
	"testing"
)

func TestGenerateUserIDAndToken(t *testing.T) {
	id1, err := GenerateUserID()
	if err != nil {
		t.Fatalf("GenerateUserID: %v", err)
	}
	id2, err := GenerateUserID()
	if err != nil {
		t.Fatalf("GenerateUserID: %v", err)
	}
	if len(id1) != 32 {
		t.Errorf("user ID length = %d, want 32", len(id1))
	}
	if id1 == id2 {
		t.Errorf("expected unique user IDs, got identical %q", id1)
	}
	if valErr := ValidateUserID(id1); valErr != nil {
		t.Errorf("generated user ID failed validation: %+v", valErr)
	}

	tok1, err := GenerateToken()
	if err != nil {
		t.Fatalf("GenerateToken: %v", err)
	}
	tok2, err := GenerateToken()
	if err != nil {
		t.Fatalf("GenerateToken: %v", err)
	}
	if len(tok1) != 64 {
		t.Errorf("token length = %d, want 64", len(tok1))
	}
	if tok1 == tok2 {
		t.Errorf("expected unique tokens")
	}
}

func TestValidateUserID(t *testing.T) {
	valid := []string{
		"a1b2c3d4e5f60718293a4b5c6d7e8f90",
		"user-1",
		"alice_pebble_2",
		"0abc",
	}
	for _, id := range valid {
		if err := ValidateUserID(id); err != nil {
			t.Errorf("ValidateUserID(%q) = %+v, want nil", id, err)
		}
	}

	invalid := []struct {
		id   string
		code string
	}{
		{"", "user_id_blank"},
		{"   ", "user_id_blank"},
		{" abc", "user_id_invalid"},
		{"..", "user_id_invalid"},
		{"../etc", "user_id_invalid"},
		{"UpperCase", "user_id_invalid"},
		{"user.name", "user_id_invalid"},
		{"-leading-dash", "user_id_invalid"},
		{strings.Repeat("a", MaxUserIDLength+1), "user_id_too_long"},
		{"api", "user_id_reserved"},
		{"register", "user_id_reserved"},
		{"healthz", "user_id_reserved"},
		{"ui", "user_id_reserved"},
		{"users", "user_id_reserved"},
	}
	for _, tc := range invalid {
		err := ValidateUserID(tc.id)
		if err == nil {
			t.Errorf("ValidateUserID(%q) = nil, want %q", tc.id, tc.code)
			continue
		}
		if err.Code != tc.code {
			t.Errorf("ValidateUserID(%q) code = %q, want %q", tc.id, err.Code, tc.code)
		}
	}
}

func TestValidateUserName(t *testing.T) {
	got, err := ValidateUserName("  Alice's Pebble  ")
	if err != nil {
		t.Fatalf("ValidateUserName valid: %+v", err)
	}
	if got != "Alice's Pebble" {
		t.Errorf("got %q, want %q", got, "Alice's Pebble")
	}

	empty, err := ValidateUserName("   ")
	if err != nil || empty != "" {
		t.Fatalf("empty name = (%q, %+v), want empty nil", empty, err)
	}

	if _, err := ValidateUserName(strings.Repeat("x", MaxUserNameLength+1)); err == nil || err.Code != "user_name_too_long" {
		t.Errorf("expected user_name_too_long, got %+v", err)
	}
	if _, err := ValidateUserName("bad\nname"); err == nil || err.Code != "user_name_invalid" {
		t.Errorf("expected user_name_invalid, got %+v", err)
	}
}

func TestValidateExternalSubject(t *testing.T) {
	got, err := ValidateExternalSubject("  Gabriel.Hidasy@Example.COM  ")
	if err != nil {
		t.Fatalf("ValidateExternalSubject valid: %+v", err)
	}
	if got != "gabriel.hidasy@example.com" {
		t.Errorf("got %q, want %q", got, "gabriel.hidasy@example.com")
	}

	if _, err := ValidateExternalSubject("   "); err == nil || err.Code != "subject_blank" {
		t.Errorf("expected subject_blank, got %+v", err)
	}
	if _, err := ValidateExternalSubject(strings.Repeat("a", MaxExternalSubjectLength+1)); err == nil || err.Code != "subject_too_long" {
		t.Errorf("expected subject_too_long, got %+v", err)
	}
	if _, err := ValidateExternalSubject("bad\x00user"); err == nil || err.Code != "subject_invalid" {
		t.Errorf("expected subject_invalid, got %+v", err)
	}
}
