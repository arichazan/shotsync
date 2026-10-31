package install

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"text/template"
)

// menuLabel is the launchd label of the opt-in menu-bar login item.
const menuLabel = "com.shotsync.menu"

var menuPlistTemplate = template.Must(template.New("menu").Parse(`<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>Label</key>
	<string>{{ .Label }}</string>
	<key>ProgramArguments</key>
	<array>
		<string>{{ .Exe }}</string>
		<string>menu</string>
	</array>
	<key>RunAtLoad</key>
	<true/>
	<key>KeepAlive</key>
	<false/>
	<key>LimitLoadToSessionType</key>
	<string>Aqua</string>
	<key>ProcessType</key>
	<string>Interactive</string>
</dict>
</plist>
`))

// LoginItemPlist returns the path of the menu-bar login item plist.
func LoginItemPlist() (string, error) {
	p, err := DefaultPaths()
	if err != nil {
		return "", err
	}
	return filepath.Join(p.AgentDir, menuLabel+".plist"), nil
}

// EnableLoginItem writes a per-user launchd agent that opens the menu-bar app
// (exe + "menu") at the next login. It does not start a second copy now.
func EnableLoginItem(exe string) error {
	if !filepath.IsAbs(exe) {
		return fmt.Errorf("login item: executable path must be absolute: %q", exe)
	}
	plist, err := LoginItemPlist()
	if err != nil {
		return err
	}
	return writeMenuPlist(plist, exe)
}

// DisableLoginItem removes the login item. A menu-bar app that is already
// running keeps running until quit.
func DisableLoginItem() error {
	plist, err := LoginItemPlist()
	if err != nil {
		return err
	}
	if err := os.Remove(plist); err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	return nil
}

// LoginItemEnabled reports whether the menu-bar login item is installed.
func LoginItemEnabled() bool {
	plist, err := LoginItemPlist()
	if err != nil {
		return false
	}
	_, err = os.Stat(plist)
	return err == nil
}

func writeMenuPlist(path, exe string) error {
	if strings.ContainsAny(exe, "<>&") {
		return fmt.Errorf("login item: unsupported characters in path %q", exe)
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	tmp := path + ".tmp"
	f, err := os.OpenFile(tmp, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o644)
	if err != nil {
		return err
	}
	if err := menuPlistTemplate.Execute(f, map[string]string{"Label": menuLabel, "Exe": exe}); err != nil {
		f.Close()
		return err
	}
	if err := f.Close(); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

// InAppBundle reports whether exe is the main executable of a .app bundle
// (…/Name.app/Contents/MacOS/exe).
func InAppBundle(exe string) bool {
	dir := filepath.Dir(filepath.Clean(exe))
	return filepath.Base(dir) == "MacOS" &&
		filepath.Base(filepath.Dir(dir)) == "Contents" &&
		strings.HasSuffix(filepath.Dir(filepath.Dir(dir)), ".app")
}
