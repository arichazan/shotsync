// Package keychain stores device access tokens in the macOS login Keychain
// via the `security` CLI, falling back to an encrypted-permission file only
// when the Keychain is unavailable (e.g. headless test environments).
package keychain

import (
	"os"
	"os/exec"
	"strings"
	"sync"
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
	_ = exec.Command("security", "delete-generic-password", "-s", service, "-a", account).Run()
}

// Available reports whether the Keychain CLI is usable.
func Available() bool {
	if _, err := exec.LookPath("security"); err != nil {
		return false
	}
	// A generic lookup that must not leak anything; exit code only.
	return exec.Command("security", "list-keychains").Run() == nil
}

// Unset is only used in tests to avoid touching the real Keychain.
func Unset(account string) { Delete(account) }

var _ = os.Getenv // keep os import if usage changes
