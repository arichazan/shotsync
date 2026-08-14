package server

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestSanitizeFilename(t *testing.T) {
	cases := []struct {
		in   string
		want string
	}{
		{"Screenshot_2026-08-14_09-45-21.png", "Screenshot_2026-08-14_09-45-21.png"},
		{"../../etc/passwd", "passwd"},
		{"..\\..\\Windows\\evil.png", "evil.png"},
		{"/absolute/path/a.png", "a.png"},
		{"..", ""},
		{".", ""},
		{"...", ""},
		{".hidden.png", "hidden.png"},
		{"a:b*c?.png", "a_b_c_.png"},
		{"tab\tname.png", "tabname.png"},
		{"ok (1).jpeg", "ok (1).jpeg"},
		{"", ""},
		{"   ", ""},
	}
	for _, c := range cases {
		if got := sanitizeFilename(c.in); got != c.want {
			t.Errorf("sanitizeFilename(%q) = %q, want %q", c.in, got, c.want)
		}
	}
}

func TestSanitizeLongFilename(t *testing.T) {
	long := string(make([]byte, 500))
	for i := range long {
		long = long[:i] + "a" + long[i+1:]
	}
	got := sanitizeFilename(long)
	if len(got) > 200 {
		t.Fatalf("sanitized filename too long: %d", len(got))
	}
}

func TestSanitizeSubfolder(t *testing.T) {
	valid := []string{"Work phone", "Device-2", "Screenshots_2026"}
	for _, name := range valid {
		if got := sanitizeSubfolder(name); got != name {
			t.Errorf("sanitizeSubfolder(%q) = %q", name, got)
		}
	}
	invalid := []string{".", "..", "nested/folder", `nested\\folder`, "/absolute", "bad:name", "line\nbreak"}
	for _, name := range invalid {
		if got := sanitizeSubfolder(name); got != "" {
			t.Errorf("sanitizeSubfolder(%q) = %q, want empty", name, got)
		}
	}
}

func TestUploadToConfiguredSubfolder(t *testing.T) {
	e := newTestEnv(t)
	_, token := e.pair(t)
	content := []byte("subfolder-data")
	resp := e.upload(t, token, "capture.png", content, "image/png", map[string]string{
		"X-ShotSync-Destination-Subfolder": "Work phone",
	})
	out := decodeUploadResponse(t, resp)
	if out["status"] != "stored" {
		t.Fatalf("upload = %v", out)
	}
	if _, err := os.Stat(filepath.Join(e.destDir, "Work phone", "capture.png")); err != nil {
		t.Fatalf("file not stored in configured subfolder: %v", err)
	}
}

func TestPathTraversalBlocked(t *testing.T) {
	e := newTestEnv(t)
	_, token := e.pair(t)
	content := []byte("data")
	resp := e.upload(t, token, "../../outside.png", content, "image/png", nil)
	out := decodeUploadResponse(t, resp)
	if out["status"] != "stored" {
		t.Fatalf("upload = %v", out)
	}
	if _, err := os.Stat(filepath.Join(e.destDir, "outside.png")); err != nil {
		t.Fatalf("file not stored inside dest dir: %v", err)
	}
	if _, err := os.Stat(filepath.Join(filepath.Dir(e.destDir), "outside.png")); err == nil {
		t.Fatal("file escaped the destination directory")
	}
}

func TestCleanTempFiles(t *testing.T) {
	dir := t.TempDir()
	fresh := filepath.Join(dir, ".shotsync-tmp-fresh")
	stale := filepath.Join(dir, ".shotsync-tmp-stale")
	keep := filepath.Join(dir, "real.png")
	for _, f := range []string{fresh, stale, keep} {
		if err := os.WriteFile(f, []byte("x"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	old := time.Now().Add(-2 * time.Hour)
	_ = os.Chtimes(stale, old, old)
	CleanTempFiles(dir, 30*time.Minute)
	if _, err := os.Stat(stale); err == nil {
		t.Fatal("stale temp file not removed")
	}
	if _, err := os.Stat(fresh); err != nil {
		t.Fatal("fresh temp file removed prematurely")
	}
	if _, err := os.Stat(keep); err != nil {
		t.Fatal("real file removed")
	}
}
