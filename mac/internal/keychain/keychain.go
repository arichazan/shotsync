// Package keychain stores device access tokens in the macOS login Keychain
// via the `security` CLI, falling back to an owner-only (0600) JSON file in
// the support directory only when the Keychain CLI is unavailable (e.g.
// headless Linux test environments). On macOS the Keychain is always used.
package keychain

import (
	"encoding/json"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"

	"github.com/arichazan/shotsync/mac/internal/config"
)

const service = "com.shotsync.receiver"

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
	cmd := exec.Command("security", "delete-generic-password", "-s", service, "-a", account)
	_ = cmd.Run() // ignore absence
	add := exec.Command("security", "add-generic-password",
		"-s", service, "-a", account, "-w", token, "-U")
	if err := add.Run(); err != nil {
		return err
	}
	return nil
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
	out, err := exec.Command("security", "find-generic-password", "-s", service, "-a", account, "-w").Output()
	if err != nil {
		return "", false
	}
	token := strings.TrimRight(string(out), "\n")
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
	_ = exec.Command("security", "delete-generic-password", "-s", service, "-a", account).Run()
}

var availableOnce = sync.OnceValue(func() bool {
	if _, err := exec.LookPath("security"); err != nil {
		return false
	}
	// A generic lookup that must not leak anything; exit code only.
	return exec.Command("security", "list-keychains").Run() == nil
})

// Available reports whether the Keychain CLI is usable (checked once).
func Available() bool { return availableOnce() }

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
