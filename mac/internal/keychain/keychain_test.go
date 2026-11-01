package keychain

import (
	"os"
	"path/filepath"
	"testing"
)

func resetCache() {
	cache.Lock()
	cache.tokens = map[string]string{}
	cache.Unlock()
}

func TestFileFallback(t *testing.T) {
	forceFile = true
	t.Cleanup(func() { forceFile = false; resetCache() })
	dir := t.TempDir()
	t.Setenv("SHOTSYNC_HOME", dir)

	if err := Set("dev-1", "tok-1"); err != nil {
		t.Fatal(err)
	}
	resetCache()
	if got, ok := Get("dev-1"); !ok || got != "tok-1" {
		t.Fatalf("Get = %q,%v", got, ok)
	}
	if err := Set("dev-1", "tok-2"); err != nil {
		t.Fatal(err)
	}
	resetCache()
	if got, ok := Get("dev-1"); !ok || got != "tok-2" {
		t.Fatalf("Get after overwrite = %q,%v", got, ok)
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
