package keychain

import (
	"os"
	"path/filepath"
	"testing"
)

func TestFileFallback(t *testing.T) {
	if Available() {
		t.Skip("real Keychain available; fallback not used")
	}
	dir := t.TempDir()
	t.Setenv("SHOTSYNC_HOME", dir)

	if err := Set("dev-1", "tok-1"); err != nil {
		t.Fatal(err)
	}
	cache.Lock()
	cache.tokens = map[string]string{}
	cache.Unlock()
	if got, ok := Get("dev-1"); !ok || got != "tok-1" {
		t.Fatalf("Get = %q,%v", got, ok)
	}
	fi, err := os.Stat(filepath.Join(dir, "tokens.json"))
	if err != nil {
		t.Fatal(err)
	}
	if fi.Mode().Perm() != 0o600 {
		t.Fatalf("perm = %v, want 0600", fi.Mode().Perm())
	}
	Delete("dev-1")
	if _, ok := Get("dev-1"); ok {
		t.Fatal("token still present after Delete")
	}
}
