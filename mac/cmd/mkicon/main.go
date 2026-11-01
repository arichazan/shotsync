// mkicon renders the ShotSync app icon (blue rounded square with a white
// dot, matching the menu-bar icon) into a macOS .iconset directory, which
// `iconutil -c icns` turns into ShotSync.icns. Used by scripts/release_mac.sh.
//
// Usage: go run ./cmd/mkicon <out.iconset>
package main

import (
	"fmt"
	"image"
	"image/color"
	"image/png"
	"os"
	"path/filepath"
)

// render draws the icon at size×size pixels with anti-aliased edges.
func render(size int) *image.RGBA {
	img := image.NewRGBA(image.Rect(0, 0, size, size))
	s := float64(size)
	margin, radius := s*0.09, s*0.2
	dotR := s * 0.16
	const ss = 4 // supersampling per axis
	blue := [3]float64{40, 130, 255}
	for y := 0; y < size; y++ {
		for x := 0; x < size; x++ {
			var bg, dot float64
			for sy := 0; sy < ss; sy++ {
				for sx := 0; sx < ss; sx++ {
					px := float64(x) + (float64(sx)+0.5)/ss
					py := float64(y) + (float64(sy)+0.5)/ss
					if inRoundRect(px, py, margin, s-margin, radius) {
						bg++
						dx, dy := px-s/2, py-s/2
						if dx*dx+dy*dy < dotR*dotR {
							dot++
						}
					}
				}
			}
			n := float64(ss * ss)
			a := bg / n
			if a == 0 {
				continue
			}
			w := dot / bg // share of covered samples that are white
			c := func(i int) uint8 { return uint8((blue[i]*(1-w) + 255*w) * a) }
			img.SetRGBA(x, y, color.RGBA{c(0), c(1), c(2), uint8(255 * a)})
		}
	}
	return img
}

func inRoundRect(x, y, lo, hi, r float64) bool {
	if x < lo || x > hi || y < lo || y > hi {
		return false
	}
	cx := clamp(x, lo+r, hi-r)
	cy := clamp(y, lo+r, hi-r)
	dx, dy := x-cx, y-cy
	return dx*dx+dy*dy <= r*r
}

func clamp(v, lo, hi float64) float64 {
	if v < lo {
		return lo
	}
	if v > hi {
		return hi
	}
	return v
}

// iconsetFiles lists the file names iconutil expects and their pixel sizes.
func iconsetFiles() map[string]int {
	m := map[string]int{}
	for _, pt := range []int{16, 32, 128, 256, 512} {
		m[fmt.Sprintf("icon_%dx%d.png", pt, pt)] = pt
		m[fmt.Sprintf("icon_%dx%d@2x.png", pt, pt)] = pt * 2
	}
	return m
}

func write(dir string) error {
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	for name, px := range iconsetFiles() {
		f, err := os.Create(filepath.Join(dir, name))
		if err != nil {
			return err
		}
		if err := png.Encode(f, render(px)); err != nil {
			f.Close()
			return err
		}
		if err := f.Close(); err != nil {
			return err
		}
	}
	return nil
}

func main() {
	if len(os.Args) != 2 {
		fmt.Fprintln(os.Stderr, "usage: mkicon <out.iconset>")
		os.Exit(2)
	}
	if err := write(os.Args[1]); err != nil {
		fmt.Fprintln(os.Stderr, "mkicon:", err)
		os.Exit(1)
	}
}
