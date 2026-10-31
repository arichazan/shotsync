package main

import (
	"image/png"
	"os"
	"path/filepath"
	"testing"
)

func TestWriteIconset(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "ShotSync.iconset")
	if err := write(dir); err != nil {
		t.Fatal(err)
	}
	files := iconsetFiles()
	if len(files) != 10 {
		t.Fatalf("got %d iconset entries, want 10", len(files))
	}
	for name, px := range files {
		f, err := os.Open(filepath.Join(dir, name))
		if err != nil {
			t.Fatal(err)
		}
		cfg, err := png.DecodeConfig(f)
		f.Close()
		if err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		if cfg.Width != px || cfg.Height != px {
			t.Fatalf("%s: %dx%d, want %d", name, cfg.Width, cfg.Height, px)
		}
	}
}

func TestRenderShape(t *testing.T) {
	img := render(256)
	if _, _, _, a := img.At(0, 0).RGBA(); a != 0 {
		t.Fatal("corner should be transparent")
	}
	if r, g, b, _ := img.At(128, 128).RGBA(); r>>8 != 255 || g>>8 != 255 || b>>8 != 255 {
		t.Fatal("center should be the white dot")
	}
	if r, _, b, a := img.At(60, 128).RGBA(); a>>8 != 255 || b>>8 != 255 || r>>8 != 40 {
		t.Fatal("body should be opaque blue")
	}
}
