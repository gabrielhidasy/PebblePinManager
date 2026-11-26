package config

import (
	"errors"
	"fmt"
	"log/slog"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"
)

const (
	DefaultAddr      = ":8080"
	DefaultDataFile  = "/data/pins.json"
	DefaultRetention = 48 * time.Hour

	maxServerNameLen = 80
)

// Config holds runtime configuration for the companion pin server.
type Config struct {
	Addr             string
	WriteToken       string
	ReadToken        string
	DataFile         string
	Retention        time.Duration
	TrustProxy       bool
	LogLevel         slog.Level
	RegistrationMode bool
	PublicURL        string
	ServerName       string
}

// LoadFromEnv loads and validates Config using the provided environment lookup function.
func LoadFromEnv(getenv func(string) string) (Config, error) {
	cfg := Config{
		Addr:      DefaultAddr,
		DataFile:  DefaultDataFile,
		Retention: DefaultRetention,
		LogLevel:  slog.LevelInfo,
	}

	if v := strings.TrimSpace(getenv("PIN_SERVER_ADDR")); v != "" {
		cfg.Addr = v
	}

	if v := strings.TrimSpace(getenv("PIN_SERVER_DATA_FILE")); v != "" {
		cfg.DataFile = v
	}

	if v := strings.TrimSpace(getenv("PIN_SERVER_RETENTION")); v != "" {
		d, err := time.ParseDuration(v)
		if err != nil {
			return Config{}, fmt.Errorf("invalid PIN_SERVER_RETENTION %q: %w", v, err)
		}
		if d < 0 {
			return Config{}, fmt.Errorf("PIN_SERVER_RETENTION must be >= 0 (got %s)", d)
		}
		cfg.Retention = d
	}

	if v := strings.TrimSpace(getenv("PIN_SERVER_TRUST_PROXY")); v != "" {
		b, err := strconv.ParseBool(v)
		if err != nil {
			return Config{}, fmt.Errorf("invalid PIN_SERVER_TRUST_PROXY %q: %w", v, err)
		}
		cfg.TrustProxy = b
	}

	if v := strings.TrimSpace(getenv("PIN_SERVER_LOG_LEVEL")); v != "" {
		level, err := parseLogLevel(v)
		if err != nil {
			return Config{}, err
		}
		cfg.LogLevel = level
	}

	if v := strings.TrimSpace(getenv("PIN_SERVER_REGISTRATION_MODE")); v != "" {
		b, err := strconv.ParseBool(v)
		if err != nil {
			return Config{}, fmt.Errorf("invalid PIN_SERVER_REGISTRATION_MODE %q: %w", v, err)
		}
		cfg.RegistrationMode = b
	}

	if v := strings.TrimSpace(getenv("PIN_SERVER_PUBLIC_URL")); v != "" {
		normalized, err := normalizePublicURL(v)
		if err != nil {
			return Config{}, fmt.Errorf("invalid PIN_SERVER_PUBLIC_URL %q: %w", v, err)
		}
		cfg.PublicURL = normalized
	}

	if v := strings.TrimSpace(getenv("PIN_SERVER_NAME")); v != "" {
		if len([]rune(v)) > maxServerNameLen {
			return Config{}, fmt.Errorf("PIN_SERVER_NAME must be at most %d characters", maxServerNameLen)
		}
		cfg.ServerName = v
	}

	writeToken, err := loadSecret(getenv, "PIN_SERVER_WRITE_TOKEN", "PIN_SERVER_WRITE_TOKEN_FILE")
	if err != nil {
		return Config{}, err
	}
	fallbackToken, err := loadSecret(getenv, "PIN_SERVER_TOKEN", "PIN_SERVER_TOKEN_FILE")
	if err != nil {
		return Config{}, err
	}
	if writeToken != "" && fallbackToken != "" {
		return Config{}, errors.New("set either PIN_SERVER_WRITE_TOKEN(_FILE) or PIN_SERVER_TOKEN(_FILE), not both")
	}
	if writeToken == "" {
		writeToken = fallbackToken
	}
	cfg.WriteToken = writeToken

	readToken, err := loadSecret(getenv, "PIN_SERVER_READ_TOKEN", "PIN_SERVER_READ_TOKEN_FILE")
	if err != nil {
		return Config{}, err
	}
	cfg.ReadToken = readToken

	if cfg.WriteToken == "" && cfg.ReadToken == "" {
		return Config{}, errors.New("authentication is required: configure PIN_SERVER_WRITE_TOKEN (or PIN_SERVER_TOKEN) and/or PIN_SERVER_READ_TOKEN")
	}

	return cfg, nil
}

// PairingToken returns the token and scope ("read" or "write") to embed in QR registration
// payloads, preferring the least-privilege ReadToken when configured.
func (c Config) PairingToken() (token string, scope string) {
	if c.ReadToken != "" {
		return c.ReadToken, "read"
	}
	if c.WriteToken != "" {
		return c.WriteToken, "write"
	}
	return "", ""
}

// RedactedSummary returns log-safe attributes describing the configuration without any secret tokens.
func (c Config) RedactedSummary() []any {
	return []any{
		"addr", c.Addr,
		"dataFile", c.DataFile,
		"retention", c.Retention.String(),
		"trustProxy", c.TrustProxy,
		"registrationMode", c.RegistrationMode,
		"publicUrlConfigured", c.PublicURL != "",
		"writeTokenConfigured", c.WriteToken != "",
		"readTokenConfigured", c.ReadToken != "",
	}
}

func normalizePublicURL(raw string) (string, error) {
	u, err := url.Parse(raw)
	if err != nil {
		return "", err
	}
	scheme := strings.ToLower(u.Scheme)
	if scheme != "https" && scheme != "http" {
		return "", errors.New("scheme must be https or http")
	}
	if u.Host == "" {
		return "", errors.New("host is required")
	}
	if u.User != nil {
		return "", errors.New("must not contain embedded credentials")
	}
	if u.RawQuery != "" || u.Fragment != "" {
		return "", errors.New("must not contain query parameters or fragments")
	}
	u.Scheme = scheme
	u.Path = strings.TrimRight(u.Path, "/")
	return u.String(), nil
}

func loadSecret(getenv func(string) string, envKey, fileKey string) (string, error) {
	direct := strings.TrimSpace(getenv(envKey))
	filePath := strings.TrimSpace(getenv(fileKey))
	if direct != "" && filePath != "" {
		return "", fmt.Errorf("set either %s or %s, not both", envKey, fileKey)
	}
	if direct != "" {
		return direct, nil
	}
	if filePath != "" {
		raw, err := os.ReadFile(filePath)
		if err != nil {
			return "", fmt.Errorf("read %s (%s): %w", fileKey, filePath, err)
		}
		val := strings.TrimSpace(string(raw))
		if val == "" {
			return "", fmt.Errorf("%s (%s) is empty", fileKey, filePath)
		}
		return val, nil
	}
	return "", nil
}

func parseLogLevel(raw string) (slog.Level, error) {
	switch strings.ToLower(strings.TrimSpace(raw)) {
	case "debug":
		return slog.LevelDebug, nil
	case "info":
		return slog.LevelInfo, nil
	case "warn", "warning":
		return slog.LevelWarn, nil
	case "error":
		return slog.LevelError, nil
	default:
		return slog.LevelInfo, fmt.Errorf("invalid PIN_SERVER_LOG_LEVEL %q (expected debug, info, warn, or error)", raw)
	}
}
