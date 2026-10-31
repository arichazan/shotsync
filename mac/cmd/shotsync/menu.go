package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"image"
	"image/color"
	"image/draw"
	"image/png"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"

	"fyne.io/systray"
	"github.com/arichazan/shotsync/mac/internal/config"
	"github.com/arichazan/shotsync/mac/internal/install"
)

type statusInfo struct {
	Paused     bool   `json:"paused"`
	DeviceName string `json:"device_name"`
	Devices    []struct {
		DeviceID string    `json:"device_id"`
		Name     string    `json:"name"`
		LastSeen time.Time `json:"last_seen,omitempty"`
	} `json:"devices"`
	LastReceived *struct {
		Filename string    `json:"filename"`
		StoredAt time.Time `json:"stored_at"`
	} `json:"last_received"`
}

// runMenu starts the menu-bar status application.
func runMenu() {
	systray.Run(onReady, onExit)
}

func onReady() {
	systray.SetIcon(appIcon())
	systray.SetTitle("ShotSync")
	systray.SetTooltip("ShotSync receiver")

	statusItem := systray.AddMenuItem("Checking…", "Receiver status")
	statusItem.Disable()
	systray.AddSeparator()

	openFolder := systray.AddMenuItem("Open Screenshots Folder", "")
	showQR := systray.AddMenuItem("Show Pairing QR Code…", "")
	systray.AddSeparator()

	pauseItem := systray.AddMenuItemCheckbox("Pause Receiving", "", false)
	pauseItem.Disable()
	systray.AddSeparator()

	startItem := systray.AddMenuItem("Start Receiver", "")
	startItem.Hide()
	stopItem := systray.AddMenuItem("Stop Receiver", "")
	systray.AddSeparator()

	settingsItem := systray.AddMenuItem("Settings", "Receiver settings")
	chooseFolderItem := settingsItem.AddSubMenuItem("Choose Destination Folder…", "Select where received media is stored")
	nameItem := settingsItem.AddSubMenuItem("Set Receiver Name…", "Choose the name shown during pairing")
	advancedItem := settingsItem.AddSubMenuItem("Edit Advanced Configuration…", "Open the receiver configuration file")
	logsItem := systray.AddMenuItem("View Logs", "")
	systray.AddSeparator()
	quitItem := systray.AddMenuItem("Quit ShotSync", "")

	cfg, _ := config.Load()
	if cfg == nil {
		cfg, _ = config.Default()
	}

	refresh := func() {
		info, err := fetchStatus()
		if err != nil {
			statusItem.SetTitle("Receiver not running")
			pauseItem.Disable()
			startItem.Show()
			stopItem.Hide()
			return
		}
		startItem.Hide()
		stopItem.Show()
		state := "Running"
		if info.Paused {
			state = "Running (paused)"
		}
		sub := fmt.Sprintf("%d device(s)", len(info.Devices))
		if info.LastReceived != nil {
			sub += " · last: " + info.LastReceived.Filename
		}
		statusItem.SetTitle(state + " · " + sub)
		pauseItem.Enable()
		if pauseItem.Checked() != info.Paused {
			if info.Paused {
				pauseItem.Check()
			} else {
				pauseItem.Uncheck()
			}
		}
	}

	go func() {
		refresh()
		ticker := time.NewTicker(10 * time.Second)
		defer ticker.Stop()
		for range ticker.C {
			refresh()
		}
	}()

	go func() {
		for range openFolder.ClickedCh {
			cfg, err := config.Load()
			if err != nil || cfg.DestDir == "" {
				continue
			}
			_ = exec.Command("open", cfg.DestDir).Start()
		}
	}()

	go func() {
		for range showQR.ClickedCh {
			go showQRCode()
		}
	}()

	go func() {
		for range pauseItem.ClickedCh {
			info, err := fetchStatus()
			if err != nil {
				continue
			}
			togglePause(!info.Paused)
		}
	}()

	go func() {
		for range startItem.ClickedCh {
			_ = install.Start()
		}
	}()
	go func() {
		for range stopItem.ClickedCh {
			_ = install.Stop()
		}
	}()

	go func() {
		for range chooseFolderItem.ClickedCh {
			cfg, err := config.Load()
			if err != nil {
				continue
			}
			path, err := chooseDestinationFolder(cfg.DestDir)
			if err == nil {
				_ = cfg.SetDestination(path)
			}
		}
	}()

	go func() {
		for range nameItem.ClickedCh {
			cfg, err := config.Load()
			if err != nil {
				continue
			}
			name, err := promptReceiverName(cfg.DeviceName)
			if err == nil {
				if cfg.SetDeviceName(name) == nil && install.IsLoaded() {
					_ = install.Start()
				}
			}
		}
	}()

	go func() {
		for range advancedItem.ClickedCh {
			cfg, err := config.Load()
			if err == nil {
				_ = exec.Command("open", "-e", cfg.Path()).Start()
			}
		}
	}()

	go func() {
		for range logsItem.ClickedCh {
			p, err := install.DefaultPaths()
			if err != nil {
				continue
			}
			_ = exec.Command("open", "-e", p.Log).Start()
		}
	}()

	go func() {
		<-quitItem.ClickedCh
		systray.Quit()
	}()
}

func onExit() {}

func chooseDestinationFolder(current string) (string, error) {
	if current == "" {
		current, _ = os.UserHomeDir()
	} else if _, err := os.Stat(current); err != nil {
		current, _ = os.UserHomeDir()
	}
	script := `on run argv
set selectedFolder to choose folder with prompt "Choose where ShotSync should store received media" default location (POSIX file (item 1 of argv))
return POSIX path of selectedFolder
end run`
	out, err := exec.Command("osascript", "-e", script, current).Output()
	if err != nil {
		return "", err
	}
	return filepath.Clean(strings.TrimSpace(string(out))), nil
}

func promptReceiverName(current string) (string, error) {
	script := `on run argv
display dialog "Name shown to devices during pairing:" default answer (item 1 of argv) buttons {"Cancel", "Save"} default button "Save"
return text returned of result
end run`
	out, err := exec.Command("osascript", "-e", script, current).Output()
	if err != nil {
		return "", err
	}
	return strings.TrimSpace(string(out)), nil
}

func fetchStatus() (*statusInfo, error) {
	cfg, err := config.Load()
	if err != nil {
		return nil, err
	}
	client := adminClient()
	resp, err := client.Get(fmt.Sprintf("https://127.0.0.1:%d/api/v1/status", cfg.Port))
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("status %d", resp.StatusCode)
	}
	var info statusInfo
	if err := json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(&info); err != nil {
		return nil, err
	}
	return &info, nil
}

func togglePause(paused bool) {
	cfg, err := config.Load()
	if err != nil {
		return
	}
	body, _ := json.Marshal(map[string]bool{"paused": paused})
	req, _ := http.NewRequest(http.MethodPost,
		fmt.Sprintf("https://127.0.0.1:%d/api/v1/admin/pause", cfg.Port), bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	resp, err := adminClient().Do(req)
	if err == nil {
		_ = resp.Body.Close()
	}
}

func showQRCode() {
	cfg, err := config.Load()
	if err != nil {
		return
	}
	client := adminClient()
	resp, err := client.Post(
		fmt.Sprintf("https://127.0.0.1:%d/api/v1/admin/pairing-issue", cfg.Port),
		"application/json", strings.NewReader("{}"))
	if err != nil {
		// Receiver not running: fall back to a locally-issued payload.
		payload, err := issuePairingLocally()
		if err != nil {
			return
		}
		openQRFiles(payload)
		return
	}
	defer resp.Body.Close()
	var payload qrPayload
	if err := json.NewDecoder(resp.Body).Decode(&payload); err != nil {
		return
	}
	openQRFiles(&payload)
}

func openQRFiles(payload *qrPayload) {
	path, err := writeQRFile(payload)
	if err != nil {
		return
	}
	_ = exec.Command("open", path).Start()
}

// appIcon renders a small 32x32 status icon (rounded square with a dot).
func appIcon() []byte {
	img := image.NewRGBA(image.Rect(0, 0, 32, 32))
	draw.Draw(img, img.Bounds(), &image.Uniform{C: color.RGBA{0, 0, 0, 0}}, image.Point{}, draw.Src)
	main := color.RGBA{40, 130, 255, 255}
	for y := 0; y < 32; y++ {
		for x := 0; x < 32; x++ {
			if x < 2 || y < 2 || x > 29 || y > 29 {
				continue
			}
			if (x > 6 && x < 25 && y > 4 && y < 27) || (y > 5 && y < 26 && x > 4 && x < 27) {
				img.Set(x, y, main)
			}
		}
	}
	dot := color.RGBA{255, 255, 255, 255}
	for y := 12; y < 20; y++ {
		for x := 12; x < 20; x++ {
			if (x-16)*(x-16)+(y-16)*(y-16) < 12 {
				img.Set(x, y, dot)
			}
		}
	}
	var buf bytes.Buffer
	_ = png.Encode(&buf, img)
	return buf.Bytes()
}
