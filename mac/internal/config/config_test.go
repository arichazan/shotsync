package config

import (
	"os"
	"path/filepath"
	"testing"
)

func TestDefaultRequiresDestinationAndUsesGenericName(t *testing.T) {
	t.Setenv("SHOTSYNC_HOME", t.TempDir())
	cfg, err := Default()
	if err != nil {
		t.Fatal(err)
	}
	if cfg.DestDir != "" {
		t.Fatalf("default destination = %q, want empty", cfg.DestDir)
	}
	if cfg.DeviceName != DefaultDeviceName {
		t.Fatalf("default device name = %q, want %q", cfg.DeviceName, DefaultDeviceName)
	}
}

func TestSetDestinationRequiresAbsoluteNonRootPath(t *testing.T) {
	t.Setenv("SHOTSYNC_HOME", t.TempDir())
	cfg, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	for _, invalid := range []string{"", "relative/path", string(filepath.Separator)} {
		if err := cfg.SetDestination(invalid); err == nil {
			t.Fatalf("SetDestination(%q) unexpectedly succeeded", invalid)
		}
	}
	dest := filepath.Join(t.TempDir(), "received media")
	if err := cfg.SetDestination(dest); err != nil {
		t.Fatal(err)
	}
	if cfg.DestDir != dest {
		t.Fatalf("destination = %q, want %q", cfg.DestDir, dest)
	}
	if info, err := os.Stat(dest); err != nil || !info.IsDir() {
		t.Fatalf("destination was not created: %v", err)
	}
	reloaded, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if reloaded.DestDir != dest {
		t.Fatalf("reloaded destination = %q, want %q", reloaded.DestDir, dest)
	}
}

func TestSetDeviceNameRejectsBlank(t *testing.T) {
	t.Setenv("SHOTSYNC_HOME", t.TempDir())
	cfg, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if err := cfg.SetDeviceName("   "); err == nil {
		t.Fatal("blank device name unexpectedly accepted")
	}
	if err := cfg.SetDeviceName("Home receiver"); err != nil {
		t.Fatal(err)
	}
	if cfg.DeviceName != "Home receiver" {
		t.Fatalf("device name = %q", cfg.DeviceName)
	}
}
