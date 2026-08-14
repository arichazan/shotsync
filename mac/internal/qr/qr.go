// Package qr renders pairing QR codes as PNG files and as terminal art.
package qr

import (
	"bytes"
	"image/png"
	"strings"

	qrcode "github.com/skip2/go-qrcode"
)

// PNG returns a QR-encoded PNG of the payload.
func PNG(payload string, size int) ([]byte, error) {
	code, err := qrcode.New(payload, qrcode.Medium)
	if err != nil {
		return nil, err
	}
	var buf bytes.Buffer
	if err := png.Encode(&buf, code.Image(size)); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

// Text renders the QR code using ANSI half-block characters for terminals.
func Text(payload string) (string, error) {
	code, err := qrcode.New(payload, qrcode.Medium)
	if err != nil {
		return "", err
	}
	bitmap := code.Bitmap()
	var sb strings.Builder
	sb.WriteString("\n")
	for y := 0; y < len(bitmap); y += 2 {
		sb.WriteString("  ")
		for x := 0; x < len(bitmap[y]); x++ {
			top := bitmap[y][x]
			bottom := false
			if y+1 < len(bitmap) {
				bottom = bitmap[y+1][x]
			}
			switch {
			case top && bottom:
				sb.WriteString(" ")
			case top && !bottom:
				sb.WriteString("\033[97;40m▀\033[0m")
			case !top && bottom:
				sb.WriteString("\033[97;40m▄\033[0m")
			default:
				sb.WriteString("\033[97;40m█\033[0m")
			}
		}
		sb.WriteString("\n")
	}
	return sb.String(), nil
}
