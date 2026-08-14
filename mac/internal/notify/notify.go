// Package notify shows native macOS notifications via osascript.
package notify

import (
	"log/slog"
	"os/exec"
	"strings"
)

// Received posts a "new screenshot" or "new screen recording" notification.
func Received(deviceName, filename, contentType string, log *slog.Logger) {
	kind := "screenshot"
	if strings.HasPrefix(contentType, "video/") {
		kind = "screen recording"
	}
	msg := "New " + kind + " from " + deviceName
	if filename != "" {
		msg += ": " + filename
	}
	if err := show("ShotSync", msg); err != nil {
		log.Debug("notification failed", "err", err)
	}
}

// Info posts a generic informational notification.
func Info(title, msg string) {
	_ = show(title, msg)
}

func show(title, msg string) error {
	script := `display notification ` + quote(msg) + ` with title ` + quote(title) + ` sound name "Pop"`
	return exec.Command("osascript", "-e", script).Run()
}

// quote produces a properly escaped AppleScript string literal.
func quote(s string) string {
	return `"` + s + `"`
}
