package install

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestWritePlist(t *testing.T) {
	dir := t.TempDir()
	p := Paths{
		AgentDir: dir,
		Plist:    filepath.Join(dir, "com.shotsync.receiver.plist"),
		BinDir:   filepath.Join(dir, "bin"),
		Bin:      filepath.Join(dir, "bin", "shotsync"),
		Log:      filepath.Join(dir, "shotsync.log"),
	}
	if err := writePlist(p); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(p.Plist)
	if err != nil {
		t.Fatal(err)
	}
	s := string(data)
	for _, want := range []string{
		"<string>com.shotsync.receiver</string>",
		"<string>" + p.Bin + "</string>",
		"<string>serve</string>",
		"<key>KeepAlive</key>",
		"<key>RunAtLoad</key>",
		"<string>" + p.Log + "</string>",
	} {
		if !strings.Contains(s, want) {
			t.Fatalf("plist missing %q\n%s", want, s)
		}
	}
}

func TestDefaultPaths(t *testing.T) {
	p, err := DefaultPaths()
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasSuffix(p.Plist, "LaunchAgents/com.shotsync.receiver.plist") {
		t.Fatalf("unexpected plist path: %s", p.Plist)
	}
	if !strings.HasSuffix(p.Bin, ".local/bin/shotsync") {
		t.Fatalf("unexpected bin path: %s", p.Bin)
	}
}

func TestExtractPID(t *testing.T) {
	out := "state = running\n\tpid = 4242\n\tpath = x"
	if got := extractPID(out); got != "4242" {
		t.Fatalf("pid = %q", got)
	}
	if got := extractPID("nothing"); got != "?" {
		t.Fatalf("pid = %q", got)
	}
}
