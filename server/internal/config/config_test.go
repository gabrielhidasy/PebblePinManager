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
	if cfg.Retention != DefaultRetention {
		t.Errorf("Retention = %v, want %v", cfg.Retention, DefaultRetention)
	}
	if cfg.WriteToken != "super-secret-write-token" {
		t.Errorf("WriteToken mismatch")
	}
	if cfg.ReadToken != "" {
		t.Errorf("ReadToken = %q, want empty", cfg.ReadToken)
	}

	summary := fmt.Sprint(cfg.RedactedSummary()...)
	if strings.Contains(summary, "super-secret-write-token") {
		t.Fatalf("RedactedSummary leaked raw token: %s", summary)
	}
}

func TestLoadFromEnv_SeparateTokensFromFiles(t *testing.T) {
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
		"PIN_SERVER_ADDR":             "127.0.0.1:9090",
		"PIN_SERVER_DATA_FILE":        "/tmp/pins.json",
		"PIN_SERVER_RETENTION":        "72h",
		"PIN_SERVER_TRUST_PROXY":      "true",
		"PIN_SERVER_LOG_LEVEL":        "debug",
		"PIN_SERVER_WRITE_TOKEN_FILE": writeFile,
		"PIN_SERVER_READ_TOKEN_FILE":  readFile,
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
	if cfg.Retention != 72*time.Hour {
		t.Errorf("Retention = %v", cfg.Retention)
	}
	if !cfg.TrustProxy {
		t.Errorf("TrustProxy = false, want true")
	}
	if cfg.LogLevel != slog.LevelDebug {
		t.Errorf("LogLevel = %v, want debug", cfg.LogLevel)
	}
	if cfg.WriteToken != "write-token-from-file" {
		t.Errorf("WriteToken = %q", cfg.WriteToken)
	}
	if cfg.ReadToken != "read-token-from-file" {
		t.Errorf("ReadToken = %q", cfg.ReadToken)
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
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			if _, err := LoadFromEnv(mapEnv(tc.env)); err == nil {
				t.Fatalf("expected error for case %q, got nil", tc.name)
			}
		})
	}
}
