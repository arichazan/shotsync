package install

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestLoginItemLifecycle(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	exe := "/Applications/ShotSync.app/Contents/MacOS/shotsync"

	if LoginItemEnabled() {
		t.Fatal("login item enabled before EnableLoginItem")
	}
	if err := EnableLoginItem(exe); err != nil {
		t.Fatal(err)
	}
	if !LoginItemEnabled() {
		t.Fatal("login item not enabled")
	}
	plist := filepath.Join(home, "Library", "LaunchAgents", "com.shotsync.menu.plist")
	data, err := os.ReadFile(plist)
	if err != nil {
		t.Fatal(err)
	}
	s := string(data)
	for _, want := range []string{
		"<string>com.shotsync.menu</string>",
		"<string>" + exe + "</string>",
		"<string>menu</string>",
		"<key>RunAtLoad</key>\n\t<true/>",
		"<key>KeepAlive</key>\n\t<false/>",
		"<string>Aqua</string>",
	} {
		if !strings.Contains(s, want) {
			t.Fatalf("plist missing %q\n%s", want, s)
		}
	}
	// Enabling twice is idempotent.
	if err := EnableLoginItem(exe); err != nil {
		t.Fatal(err)
	}
	if err := DisableLoginItem(); err != nil {
		t.Fatal(err)
	}
	if LoginItemEnabled() {
		t.Fatal("login item still enabled")
	}
	if err := DisableLoginItem(); err != nil {
		t.Fatalf("second disable: %v", err)
	}
}

func TestEnableLoginItemRejectsBadPaths(t *testing.T) {
	t.Setenv("HOME", t.TempDir())
	for _, exe := range []string{"shotsync", "/tmp/a<b>/shotsync"} {
		if err := EnableLoginItem(exe); err == nil {
			t.Fatalf("EnableLoginItem(%q) succeeded", exe)
		}
	}
}

func TestInAppBundle(t *testing.T) {
	cases := map[string]bool{
		"/Applications/ShotSync.app/Contents/MacOS/shotsync": true,
		"/Users/a/Apps/ShotSync.app/Contents/MacOS/shotsync": true,
		"/Users/a/.local/bin/shotsync":                       false,
		"/opt/homebrew/bin/shotsync":                         false,
		"/x/Contents/MacOS/shotsync":                         false,
	}
	for exe, want := range cases {
		if got := InAppBundle(exe); got != want {
			t.Errorf("InAppBundle(%q) = %v, want %v", exe, got, want)
		}
	}
}
