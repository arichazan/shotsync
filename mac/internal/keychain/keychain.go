// Package keychain stores device access tokens.
//
// On macOS (darwin with cgo) tokens live in the login Keychain as generic
// passwords, written and read through the Security framework
// (SecItemAdd/SecItemUpdate/SecItemCopyMatching/SecItemDelete), so a token
// never appears on a command line or in the process list.
//
// Everywhere else (Linux test environments, CGO_ENABLED=0 builds) tokens
// fall back to an owner-only (0600) JSON file in the support directory.
package keychain

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sync"

	"github.com/arichazan/shotsync/mac/internal/config"
)

// service is the Keychain service name. Tests override it so they never
// touch real receiver items.
var service = "com.shotsync.receiver"

// forceFile makes the package use the file fallback even when a native
// Keychain backend exists. Test-only.
var forceFile = false

var cache = struct {
	sync.Mutex
	tokens map[string]string
}{
	tokens: map[string]string{},
}

// Set stores a token for a device.
func Set(account, token string) error {
	cache.Lock()
	delete(cache.tokens, account)
	cache.Unlock()
	if !Available() {
		return fileUpdate(func(m map[string]string) { m[account] = token })
	}
	return nativeSet(service, account, token)
}

// Get retrieves a token for a device, consulting the in-memory cache first.
func Get(account string) (string, bool) {
	cache.Lock()
	if t, ok := cache.tokens[account]; ok {
		cache.Unlock()
		return t, true
	}
	cache.Unlock()
	if !Available() {
		m, err := fileLoad()
		if err != nil {
			return "", false
		}
		t, ok := m[account]
		return t, ok
	}
	token, ok := nativeGet(service, account)
	if !ok {
		return "", false
	}
	cache.Lock()
	cache.tokens[account] = token
	cache.Unlock()
	return token, true
}

// Delete removes a token for a device.
func Delete(account string) {
	cache.Lock()
	delete(cache.tokens, account)
	cache.Unlock()
	if !Available() {
		_ = fileUpdate(func(m map[string]string) { delete(m, account) })
		return
	}
	nativeDelete(service, account)
}

// Available reports whether the native Keychain backend is in use.
func Available() bool { return !forceFile && nativeAvailable }

var fileMu sync.Mutex

func filePath() (string, error) {
	dir, err := config.Dir()
	if err != nil {
		return "", err
	}
	return filepath.Join(dir, "tokens.json"), nil
}

func fileLoad() (map[string]string, error) {
	p, err := filePath()
	if err != nil {
		return nil, err
	}
	m := map[string]string{}
	b, err := os.ReadFile(p)
	if errors.Is(err, os.ErrNotExist) {
		return m, nil
	}
	if err != nil {
		return nil, err
	}
	if err := json.Unmarshal(b, &m); err != nil {
		return nil, err
	}
	return m, nil
}

func fileUpdate(fn func(map[string]string)) error {
	fileMu.Lock()
	defer fileMu.Unlock()
	m, err := fileLoad()
	if err != nil {
		return err
	}
	fn(m)
	p, err := filePath()
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(p), 0o700); err != nil {
		return err
	}
	b, _ := json.Marshal(m)
	tmp := p + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, p)
}

// Unset is only used in tests to avoid touching the real Keychain.
func Unset(account string) { Delete(account) }
