package config

import (
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func mapEnv(m map[string]string) func(string) string {
	return func(k string) string {
		return m[k]
	}
}

func TestLoadFromEnv_DefaultsAndSingleToken(t *testing.T) {
	cfg, err := LoadFromEnv(mapEnv(map[string]string{
		"PIN_SERVER_TOKEN": "super-secret-write-token",
	}))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if cfg.Addr != DefaultAddr {
		t.Errorf("Addr = %q, want %q", cfg.Addr, DefaultAddr)
	}
	if cfg.DataFile != DefaultDataFile {
		t.Errorf("DataFile = %q, want %q", cfg.DataFile, DefaultDataFile)
	}
	if cfg.DataDir != "/data" {
		t.Errorf("DataDir = %q, want %q", cfg.DataDir, "/data")
	}
	if cfg.MaxUsers != DefaultMaxUsers {
		t.Errorf("MaxUsers = %d, want %d", cfg.MaxUsers, DefaultMaxUsers)
	}
	if cfg.Retention != DefaultRetention {
		t.Errorf("Retention = %v, want %v", cfg.Retention, DefaultRetention)
	}
	if cfg.WriteToken != "super-secret-write-token" {
		t.Errorf("WriteToken mismatch")
	}
	if cfg.ReadToken != "" {
		t.Errorf("ReadToken = %q, want empty", cfg.ReadToken)
	}
	if cfg.RegistrationMode {
		t.Errorf("RegistrationMode = true, want false by default")
	}
	if !cfg.WebUIEnabled {
		t.Errorf("WebUIEnabled = false, want true by default")
	}
	if cfg.AuthProxyHeader != "" || cfg.AuthProxyNameHeader != "" {
		t.Errorf("expected empty auth proxy headers by default, got %q / %q", cfg.AuthProxyHeader, cfg.AuthProxyNameHeader)
	}
	if tok, scope := cfg.PairingToken(); tok != "super-secret-write-token" || scope != "write" {
		t.Errorf("PairingToken() = (%q, %q), want write token fallback", tok, scope)
	}

	summary := fmt.Sprint(cfg.RedactedSummary()...)
	if strings.Contains(summary, "super-secret-write-token") {
		t.Fatalf("RedactedSummary leaked raw token: %s", summary)
	}
}

func TestLoadFromEnv_SeparateTokensFromFilesAndRegistrationMode(t *testing.T) {
	dir := t.TempDir()
	writeFile := filepath.Join(dir, "write.secret")
	readFile := filepath.Join(dir, "read.secret")
	if err := os.WriteFile(writeFile, []byte("write-token-from-file\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(readFile, []byte("read-token-from-file\n"), 0o600); err != nil {
		t.Fatal(err)
	}

	cfg, err := LoadFromEnv(mapEnv(map[string]string{
		"PIN_SERVER_ADDR":              "127.0.0.1:9090",
		"PIN_SERVER_DATA_FILE":         "/tmp/pins.json",
		"PIN_SERVER_DATA_DIR":          "/tmp/pebble-data",
		"PIN_SERVER_MAX_USERS":         "250",
		"PIN_SERVER_RETENTION":         "72h",
		"PIN_SERVER_TRUST_PROXY":       "true",
		"PIN_SERVER_LOG_LEVEL":         "debug",
		"PIN_SERVER_REGISTRATION_MODE": "true",
		"PIN_SERVER_PUBLIC_URL":        "https://pins.example.com/pebble/",
		"PIN_SERVER_NAME":              "Home Pin Server",
		"PIN_SERVER_WRITE_TOKEN_FILE":  writeFile,
		"PIN_SERVER_READ_TOKEN_FILE":   readFile,
	}))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if cfg.Addr != "127.0.0.1:9090" {
		t.Errorf("Addr = %q", cfg.Addr)
	}
	if cfg.DataFile != "/tmp/pins.json" {
		t.Errorf("DataFile = %q", cfg.DataFile)
	}
	if cfg.DataDir != "/tmp/pebble-data" {
		t.Errorf("DataDir = %q", cfg.DataDir)
	}
	if cfg.MaxUsers != 250 {
		t.Errorf("MaxUsers = %d", cfg.MaxUsers)
	}
	if cfg.Retention != 72*time.Hour {
		t.Errorf("Retention = %v", cfg.Retention)
	}
	if !cfg.TrustProxy {
		t.Errorf("TrustProxy = false, want true")
	}
	if cfg.LogLevel != slog.LevelDebug {
		t.Errorf("LogLevel = %v, want debug", cfg.LogLevel)
	}
	if !cfg.RegistrationMode {
		t.Errorf("RegistrationMode = false, want true")
	}
	if cfg.PublicURL != "https://pins.example.com/pebble" {
		t.Errorf("PublicURL = %q, want trimmed URL", cfg.PublicURL)
	}
	if cfg.ServerName != "Home Pin Server" {
		t.Errorf("ServerName = %q", cfg.ServerName)
	}
	if cfg.WriteToken != "write-token-from-file" {
		t.Errorf("WriteToken = %q", cfg.WriteToken)
	}
	if cfg.ReadToken != "read-token-from-file" {
		t.Errorf("ReadToken = %q", cfg.ReadToken)
	}
	if tok, scope := cfg.PairingToken(); tok != "read-token-from-file" || scope != "read" {
		t.Errorf("PairingToken() = (%q, %q), want read token preference", tok, scope)
	}
}

func TestLoadFromEnv_Errors(t *testing.T) {
	tests := []struct {
		name string
		env  map[string]string
	}{
		{
			name: "missing all tokens",
			env:  map[string]string{},
		},
		{
			name: "both write token and fallback token",
			env: map[string]string{
				"PIN_SERVER_WRITE_TOKEN": "aaa",
				"PIN_SERVER_TOKEN":       "bbb",
			},
		},
		{
			name: "both direct and file write token",
			env: map[string]string{
				"PIN_SERVER_WRITE_TOKEN":      "aaa",
				"PIN_SERVER_WRITE_TOKEN_FILE": "/nonexistent",
			},
		},
		{
			name: "invalid retention",
			env: map[string]string{
				"PIN_SERVER_TOKEN":     "secret",
				"PIN_SERVER_RETENTION": "not-a-duration",
			},
		},
		{
			name: "negative retention",
			env: map[string]string{
				"PIN_SERVER_TOKEN":     "secret",
				"PIN_SERVER_RETENTION": "-1h",
			},
		},
		{
			name: "invalid trust proxy",
			env: map[string]string{
				"PIN_SERVER_TOKEN":       "secret",
				"PIN_SERVER_TRUST_PROXY": "maybe",
			},
		},
		{
			name: "invalid log level",
			env: map[string]string{
				"PIN_SERVER_TOKEN":     "secret",
				"PIN_SERVER_LOG_LEVEL": "verbose",
			},
		},
		{
			name: "invalid registration mode",
			env: map[string]string{
				"PIN_SERVER_TOKEN":             "secret",
				"PIN_SERVER_REGISTRATION_MODE": "sometimes",
			},
		},
		{
			name: "invalid public url scheme",
			env: map[string]string{
				"PIN_SERVER_TOKEN":      "secret",
				"PIN_SERVER_PUBLIC_URL": "ftp://pins.example.com",
			},
		},
		{
			name: "public url with credentials",
			env: map[string]string{
				"PIN_SERVER_TOKEN":      "secret",
				"PIN_SERVER_PUBLIC_URL": "https://user:pass@pins.example.com",
			},
		},
		{
			name: "public url with query",
			env: map[string]string{
				"PIN_SERVER_TOKEN":      "secret",
				"PIN_SERVER_PUBLIC_URL": "https://pins.example.com?foo=bar",
			},
		},
		{
			name: "server name too long",
			env: map[string]string{
				"PIN_SERVER_TOKEN": "secret",
				"PIN_SERVER_NAME":  strings.Repeat("a", 81),
			},
		},
		{
			name: "invalid max users",
			env: map[string]string{
				"PIN_SERVER_TOKEN":     "secret",
				"PIN_SERVER_MAX_USERS": "many",
			},
		},
		{
			name: "negative max users",
			env: map[string]string{
				"PIN_SERVER_TOKEN":     "secret",
				"PIN_SERVER_MAX_USERS": "-1",
			},
		},
		{
			name: "invalid web ui bool",
			env: map[string]string{
				"PIN_SERVER_TOKEN":  "secret",
				"PIN_SERVER_WEB_UI": "sometimes",
			},
		},
		{
			name: "auth proxy header without trust proxy",
			env: map[string]string{
				"PIN_SERVER_TOKEN":             "secret",
				"PIN_SERVER_AUTH_PROXY_HEADER": "Remote-User",
			},
		},
		{
			name: "invalid auth proxy header name",
			env: map[string]string{
				"PIN_SERVER_TOKEN":             "secret",
				"PIN_SERVER_TRUST_PROXY":       "true",
				"PIN_SERVER_AUTH_PROXY_HEADER": "Bad Header: Value",
			},
		},
		{
			name: "auth proxy name header without auth proxy header",
			env: map[string]string{
				"PIN_SERVER_TOKEN":                  "secret",
				"PIN_SERVER_TRUST_PROXY":            "true",
				"PIN_SERVER_AUTH_PROXY_NAME_HEADER": "Remote-Name",
			},
		},
		{
			name: "both direct and file token encryption key",
			env: map[string]string{
				"PIN_SERVER_TOKEN":                     "secret",
				"PIN_SERVER_TOKEN_ENCRYPTION_KEY":      "abc",
				"PIN_SERVER_TOKEN_ENCRYPTION_KEY_FILE": "/nonexistent",
			},
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			if _, err := LoadFromEnv(mapEnv(tc.env)); err == nil {
				t.Fatalf("expected error for case %q, got nil", tc.name)
			}
		})
	}
}

func TestLoadFromEnv_WebUIAuthProxyAndTokenEncryptionKey(t *testing.T) {
	cfg, err := LoadFromEnv(mapEnv(map[string]string{
		"PIN_SERVER_TOKEN":                  "secret-write",
		"PIN_SERVER_TRUST_PROXY":            "true",
		"PIN_SERVER_WEB_UI":                 "false",
		"PIN_SERVER_AUTH_PROXY_HEADER":      "Remote-User",
		"PIN_SERVER_AUTH_PROXY_NAME_HEADER": "Remote-Name",
		"PIN_SERVER_TOKEN_ENCRYPTION_KEY":   "super-secret-seal-key",
	}))
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if cfg.WebUIEnabled {
		t.Errorf("WebUIEnabled = true, want false")
	}
	if cfg.AuthProxyHeader != "Remote-User" {
		t.Errorf("AuthProxyHeader = %q, want Remote-User", cfg.AuthProxyHeader)
	}
	if cfg.AuthProxyNameHeader != "Remote-Name" {
		t.Errorf("AuthProxyNameHeader = %q, want Remote-Name", cfg.AuthProxyNameHeader)
	}
	if cfg.TokenEncryptionKey != "super-secret-seal-key" {
		t.Errorf("TokenEncryptionKey mismatch")
	}
	summary := fmt.Sprint(cfg.RedactedSummary()...)
	if strings.Contains(summary, "super-secret-seal-key") {
		t.Fatalf("RedactedSummary leaked token encryption key: %s", summary)
	}
}
