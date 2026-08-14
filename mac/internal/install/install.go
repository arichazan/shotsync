// Package install manages the launchd agent that keeps the receiver
// running when the user logs in.
package install

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"text/template"
)

const (
	label   = "com.shotsync.receiver"
	binName = "shotsync"
)

var plistTemplate = template.Must(template.New("plist").Parse(`<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>Label</key>
	<string>{{ .Label }}</string>
	<key>ProgramArguments</key>
	<array>
		<string>{{ .Bin }}</string>
		<string>serve</string>
	</array>
	<key>RunAtLoad</key>
	<true/>
	<key>KeepAlive</key>
	<true/>
	<key>ProcessType</key>
	<string>Background</string>
	<key>StandardOutPath</key>
	<string>{{ .Log }}</string>
	<key>StandardErrorPath</key>
	<string>{{ .Log }}</string>
	<key>ThrottleInterval</key>
	<integer>10</integer>
</dict>
</plist>
`))

// Paths bundles the well-known installation paths.
type Paths struct {
	AgentDir string // ~/Library/LaunchAgents
	Plist    string
	BinDir   string // ~/.local/bin
	Bin      string
	Log      string
}

// DefaultPaths computes installation paths for the current user.
func DefaultPaths() (Paths, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return Paths{}, err
	}
	agentDir := filepath.Join(home, "Library", "LaunchAgents")
	binDir := filepath.Join(home, ".local", "bin")
	return Paths{
		AgentDir: agentDir,
		Plist:    filepath.Join(agentDir, label+".plist"),
		BinDir:   binDir,
		Bin:      filepath.Join(binDir, binName),
		Log:      filepath.Join(home, "Library", "Logs", "shotsync.log"),
	}, nil
}

// Install copies the running binary, writes the plist and bootstraps the agent.
// It never requires root.
func Install(selfPath string) error {
	p, err := DefaultPaths()
	if err != nil {
		return err
	}
	if err := os.MkdirAll(p.BinDir, 0o755); err != nil {
		return err
	}
	if err := os.MkdirAll(p.AgentDir, 0o755); err != nil {
		return err
	}
	// Copy ourselves to ~/.local/bin unless already there.
	if filepath.Clean(selfPath) != filepath.Clean(p.Bin) {
		if err := copyFile(selfPath, p.Bin, 0o755); err != nil {
			return fmt.Errorf("copy binary: %w", err)
		}
	}
	if err := writePlist(p); err != nil {
		return err
	}
	// Bootout any stale registration, then bootstrap.
	_ = exec.Command("launchctl", "bootout", "gui/"+uid(), p.Plist).Run()
	out, err := exec.Command("launchctl", "bootstrap", "gui/"+uid(), p.Plist).CombinedOutput()
	if err != nil {
		// Already bootstrapped is not fatal.
		return fmt.Errorf("bootstrap: %s", string(out))
	}
	return nil
}

// Uninstall stops and removes the agent. keepData preserves state files.
func Uninstall(keepData bool) error {
	p, err := DefaultPaths()
	if err != nil {
		return err
	}
	_ = exec.Command("launchctl", "bootout", "gui/"+uid(), p.Plist).Run()
	_ = os.Remove(p.Plist)
	_ = os.Remove(p.Bin)
	if !keepData {
		home, _ := os.UserHomeDir()
		_ = os.RemoveAll(filepath.Join(home, "Library", "Application Support", "ShotSync"))
	}
	return nil
}

// Start bootstraps the agent if not running.
func Start() error {
	p, err := DefaultPaths()
	if err != nil {
		return err
	}
	if _, err := os.Stat(p.Plist); err != nil {
		return fmt.Errorf("not installed; run `shotsync install` first")
	}
	if !IsLoaded() {
		return exec.Command("launchctl", "bootstrap", "gui/"+uid(), p.Plist).Run()
	}
	return exec.Command("launchctl", "kickstart", "-k", "gui/"+uid()+"/"+label).Run()
}

// Stop unloads the agent (keeps it installed).
func Stop() error {
	p, err := DefaultPaths()
	if err != nil {
		return err
	}
	return exec.Command("launchctl", "bootout", "gui/"+uid(), p.Plist).Run()
}

// IsLoaded reports whether the agent is registered with launchd.
func IsLoaded() bool {
	err := exec.Command("launchctl", "print", "gui/"+uid()+"/"+label).Run()
	return err == nil
}

// Status returns a human-readable status line.
func Status() (string, error) {
	p, err := DefaultPaths()
	if err != nil {
		return "", err
	}
	if _, err := os.Stat(p.Plist); err != nil {
		return "not installed", nil
	}
	out, err := exec.Command("launchctl", "print", "gui/"+uid()+"/"+label).Output()
	if err != nil {
		return "installed (not loaded)", nil
	}
	state := "loaded"
	if len(out) > 0 {
		state += " (pid: " + extractPID(string(out)) + ")"
	}
	return state, nil
}

// LogTail returns the last n bytes of the log file.
func LogTail(n int64) (string, error) {
	p, err := DefaultPaths()
	if err != nil {
		return "", err
	}
	f, err := os.Open(p.Log)
	if err != nil {
		return "", err
	}
	defer f.Close()
	fi, err := f.Stat()
	if err != nil {
		return "", err
	}
	start := int64(0)
	if fi.Size() > n {
		start = fi.Size() - n
	}
	if _, err := f.Seek(start, 0); err != nil {
		return "", err
	}
	buf := make([]byte, fi.Size()-start)
	if _, err := f.Read(buf); err != nil {
		return "", err
	}
	return string(buf), nil
}

func writePlist(p Paths) error {
	f, err := os.Create(p.Plist)
	if err != nil {
		return err
	}
	defer f.Close()
	if err := plistTemplate.Execute(f, map[string]string{
		"Label": label,
		"Bin":   p.Bin,
		"Log":   p.Log,
	}); err != nil {
		return err
	}
	return nil
}

func copyFile(src, dst string, mode os.FileMode) error {
	data, err := os.ReadFile(src)
	if err != nil {
		return err
	}
	tmp := dst + ".tmp"
	if err := os.WriteFile(tmp, data, mode); err != nil {
		return err
	}
	_ = os.Remove(dst)
	return os.Rename(tmp, dst)
}

func uid() string {
	return fmt.Sprintf("%d", os.Getuid())
}

func extractPID(out string) string {
	for _, line := range splitLines(out) {
		line = strings.TrimSpace(line)
		if strings.HasPrefix(line, "pid = ") {
			return strings.TrimSpace(strings.TrimPrefix(line, "pid = "))
		}
	}
	return "?"
}

func splitLines(s string) []string {
	var out []string
	start := 0
	for i := 0; i < len(s); i++ {
		if s[i] == '\n' {
			out = append(out, s[start:i])
			start = i + 1
		}
	}
	return append(out, s[start:])
}
