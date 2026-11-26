package store

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

const (
	sealPrefix  = "v1:"
	sealKeyFile = ".token-seal.key"
)

func deriveSealKeyFromSecret(secret string) [32]byte {
	return sha256.Sum256([]byte("pebble-pin-server:token-seal:v1:" + secret))
}

func loadSealKeyFile(path string) ([32]byte, bool, error) {
	var key [32]byte
	raw, err := os.ReadFile(path)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return key, false, nil
		}
		return key, false, fmt.Errorf("read token seal key %s: %w", path, err)
	}
	decoded, err := hex.DecodeString(strings.TrimSpace(string(raw)))
	if err != nil {
		return key, false, fmt.Errorf("decode token seal key %s: %w", path, err)
	}
	if len(decoded) != 32 {
		return key, false, fmt.Errorf("invalid token seal key %s: expected 32 bytes, got %d", path, len(decoded))
	}
	copy(key[:], decoded)
	return key, true, nil
}

func createSealKeyFile(dataDir, path string) ([32]byte, error) {
	var key [32]byte
	if _, err := rand.Read(key[:]); err != nil {
		return key, fmt.Errorf("generate token seal key: %w", err)
	}
	if err := os.MkdirAll(dataDir, 0o700); err != nil {
		return key, fmt.Errorf("create data dir for seal key: %w", err)
	}

	encoded := []byte(hex.EncodeToString(key[:]) + "\n")
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
	if err != nil {
		if errors.Is(err, os.ErrExist) {
			existing, ok, loadErr := loadSealKeyFile(path)
			if loadErr != nil {
				return key, loadErr
			}
			if ok {
				return existing, nil
			}
		}
		return key, fmt.Errorf("create token seal key %s: %w", path, err)
	}

	writeErr := func() error {
		if _, err := f.Write(encoded); err != nil {
			return err
		}
		if err := f.Sync(); err != nil {
			return err
		}
		return f.Close()
	}()
	if writeErr != nil {
		_ = f.Close()
		_ = os.Remove(path)
		return key, fmt.Errorf("write token seal key %s: %w", path, writeErr)
	}

	if dirFile, err := os.Open(dataDir); err == nil {
		_ = dirFile.Sync()
		_ = dirFile.Close()
	}
	return key, nil
}

func sealToken(key [32]byte, userID, scope, plaintext string) (string, error) {
	block, err := aes.NewCipher(key[:])
	if err != nil {
		return "", fmt.Errorf("init token cipher: %w", err)
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", fmt.Errorf("init token gcm: %w", err)
	}

	nonce := make([]byte, gcm.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		return "", fmt.Errorf("generate token nonce: %w", err)
	}

	aad := []byte(userID + ":" + scope)
	sealed := gcm.Seal(nonce, nonce, []byte(plaintext), aad)
	return sealPrefix + base64.RawStdEncoding.EncodeToString(sealed), nil
}

func openToken(key [32]byte, userID, scope, encoded string) (string, error) {
	if !strings.HasPrefix(encoded, sealPrefix) {
		return "", errors.New("unsupported sealed token format")
	}
	raw, err := base64.RawStdEncoding.DecodeString(strings.TrimPrefix(encoded, sealPrefix))
	if err != nil {
		return "", fmt.Errorf("decode sealed token: %w", err)
	}

	block, err := aes.NewCipher(key[:])
	if err != nil {
		return "", fmt.Errorf("init token cipher: %w", err)
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", fmt.Errorf("init token gcm: %w", err)
	}
	if len(raw) < gcm.NonceSize() {
		return "", errors.New("sealed token payload too short")
	}

	nonce := raw[:gcm.NonceSize()]
	ciphertext := raw[gcm.NonceSize():]
	aad := []byte(userID + ":" + scope)
	plain, err := gcm.Open(nil, nonce, ciphertext, aad)
	if err != nil {
		return "", fmt.Errorf("decrypt sealed token: %w", err)
	}
	return string(plain), nil
}

func sealKeyPath(dataDir string) string {
	return filepath.Join(dataDir, sealKeyFile)
}
