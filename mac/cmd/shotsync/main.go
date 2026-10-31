// shotsync is the macOS receiver for Android-to-Mac instant screenshot sync.
// Commands:
//
//	serve                     run the receiver in the foreground (default)
//	install / uninstall       manage the launchd login agent
//	start / stop / status     control the launchd agent
//	logs                      show recent receiver logs
//	gen-qr                    print a pairing QR code in the terminal
//	devices                   list paired Android devices
//	unpair <device-id>        revoke a device token and unpair
//	menu                      run the menu-bar status app
//	version                   print version
package main

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"

	"example.com/shotsync/mac/internal/bonjour"
	"example.com/shotsync/mac/internal/config"
	"example.com/shotsync/mac/internal/identity"
	"example.com/shotsync/mac/internal/install"
	"example.com/shotsync/mac/internal/keychain"
	"example.com/shotsync/mac/internal/notify"
	"example.com/shotsync/mac/internal/qr"
	"example.com/shotsync/mac/internal/server"
	"example.com/shotsync/mac/internal/store"
)

// version is overridden at release time via -ldflags "-X main.version=...".
var version = "0.1.0"

type tokenStore struct{}

func (tokenStore) Get(account string) (string, bool) { return keychain.Get(account) }
func (tokenStore) Set(account, token string) error   { return keychain.Set(account, token) }
func (tokenStore) Delete(account string)             { keychain.Delete(account) }

func main() {
	args := os.Args[1:]
	if len(args) == 0 {
		args = []string{"serve"}
	}
	cmd, rest := args[0], args[1:]
	switch cmd {
	case "serve", "run":
		runServe()
	case "install":
		cmdInstall(rest)
	case "configure":
		cmdConfigure(rest)
	case "uninstall":
		keep := len(rest) > 0 && rest[0] == "--purge"
		if err := install.Uninstall(!keep); err != nil {
			fatal(err)
		}
		fmt.Println("shotsync uninstalled" + map[bool]string{true: " (data removed)", false: " (data kept)"}[keep])
	case "start":
		checkInstall(install.Start())
	case "stop":
		checkInstall(install.Stop())
	case "status":
		status, err := install.Status()
		if err != nil {
			fatal(err)
		}
		fmt.Println(status)
		cfg, err := config.Load()
		if err == nil {
			fmt.Printf("config: %s\ndestination: %s\n", cfg.Path(), cfg.DestDir)
		}
	case "logs":
		out, err := install.LogTail(64 * 1024)
		if err != nil {
			fatal(err)
		}
		fmt.Print(out)
	case "gen-qr":
		cmdGenQR()
	case "devices":
		cmdDevices()
	case "unpair":
		if len(rest) != 1 {
			fatal(fmt.Errorf("usage: shotsync unpair <device-id>"))
		}
		cmdUnpair(rest[0])
	case "menu":
		runMenu()
	case "version":
		fmt.Println("shotsync " + version)
	case "help", "-h", "--help":
		usage()
	default:
		fmt.Fprintf(os.Stderr, "unknown command %q\n\n", cmd)
		usage()
		os.Exit(2)
	}
}

func usage() {
	fmt.Print(`shotsync - Android-to-Mac screenshot sync receiver

Usage:
  shotsync [serve]            Run the receiver in the foreground
  shotsync install --destination /absolute/path [--receiver-name "Name"]
                              Configure and install the launchd login agent
  shotsync configure [--destination /absolute/path] [--receiver-name "Name"]
                              Update receiver settings
  shotsync uninstall          Remove the launchd agent (data kept)
  shotsync start|stop|status  Control the launchd agent
  shotsync logs               Show recent receiver logs
  shotsync gen-qr             Print a pairing QR code in the terminal
  shotsync devices            List paired Android devices
  shotsync unpair <id>        Revoke a device token and unpair
  shotsync menu               Run the menu-bar status app
  shotsync version            Print the version
`)
}

func fatal(err error) {
	fmt.Fprintln(os.Stderr, "shotsync:", err)
	os.Exit(1)
}

func checkInstall(err error) {
	if err != nil {
		fatal(err)
	}
}

// --- serve ---

func runServe() {
	cfg, err := config.Load()
	if err != nil {
		fatal(err)
	}
	if _, err := config.ValidateDestination(cfg.DestDir); err != nil {
		fatal(fmt.Errorf("receiver is not configured: %w; run `shotsync configure --destination /absolute/path`", err))
	}
	dir, err := config.Dir()
	if err != nil {
		fatal(err)
	}
	id, err := identity.LoadOrCreate(dir)
	if err != nil {
		fatal(err)
	}
	st, err := store.Open(dir)
	if err != nil {
		fatal(err)
	}

	// Logging: JSON under launchd (to the log file), text when interactive.
	var logger *slog.Logger
	if isLaunchd() {
		logger = slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo}))
	} else {
		logger = slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: levelOf(cfg.LogLevel)}))
	}

	// Remove incomplete temporary files left by crashes.
	server.CleanTempFiles(cfg.DestDir, 30*time.Minute)

	srv := server.New(cfg, id, st, tokenStore{}, logger)
	srv.SetNotifier(func(deviceID, filename, contentType string) {
		name := deviceID
		if d := st.GetDevice(deviceID); d != nil && d.Name != "" {
			name = d.Name
		}
		notify.Received(name, filename, contentType, logger)
	})

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	// Advertise over Bonjour so Android can discover us on the LAN.
	adv := bonjour.New(cfg.DeviceName, identity.NetworkHost(id.DeviceID), cfg.Port, map[string]string{
		"protocol_version": "1",
		"device_id":        id.DeviceID,
		"device_name":      cfg.DeviceName,
		"port":             strconv.Itoa(cfg.Port),
	}, logger)
	if err := adv.Start(ctx); err != nil {
		logger.Warn("bonjour advertising disabled", "err", err)
	} else {
		defer adv.Stop()
	}

	// Reload config when the preferences file changes (port/bind excluded).
	go watchConfig(ctx, cfg, logger)

	addr := fmt.Sprintf("%s:%d", cfg.BindAddr, cfg.Port)
	if err := srv.ListenAndServe(ctx, addr); err != nil {
		fatal(err)
	}
}

func isLaunchd() bool {
	return os.Getenv("LAUNCHD_SOCKET") != "" || os.Getenv("SHOTSYNC_LAUNCHD") == "1"
}

func levelOf(s string) slog.Level {
	switch strings.ToLower(s) {
	case "debug":
		return slog.LevelDebug
	case "warn":
		return slog.LevelWarn
	case "error":
		return slog.LevelError
	default:
		return slog.LevelInfo
	}
}

func watchConfig(ctx context.Context, cfg *config.Config, logger *slog.Logger) {
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()
	var lastMod time.Time
	if fi, err := os.Stat(cfg.Path()); err == nil {
		lastMod = fi.ModTime()
	}
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			fi, err := os.Stat(cfg.Path())
			if err != nil || fi.ModTime().Equal(lastMod) {
				continue
			}
			lastMod = fi.ModTime()
			if err := cfg.Reload(); err != nil {
				logger.Warn("config reload failed", "err", err)
				continue
			}
			logger.Info("config reloaded")
		}
	}
}

// --- install ---

func cmdInstall(args []string) {
	fs := flag.NewFlagSet("install", flag.ContinueOnError)
	fs.SetOutput(io.Discard)
	destination := fs.String("destination", "", "absolute folder where received media is stored")
	receiverName := fs.String("receiver-name", "", "privacy-safe receiver display name")
	if err := fs.Parse(args); err != nil || fs.NArg() != 0 {
		fatal(fmt.Errorf("usage: shotsync install --destination /absolute/path [--receiver-name \"Name\"]"))
	}
	cfg, err := config.Load()
	if err != nil {
		fatal(err)
	}
	if *destination != "" {
		if err := cfg.SetDestination(*destination); err != nil {
			fatal(err)
		}
	}
	if *receiverName != "" {
		if err := cfg.SetDeviceName(*receiverName); err != nil {
			fatal(err)
		}
	}
	if _, err := config.ValidateDestination(cfg.DestDir); err != nil {
		fatal(fmt.Errorf("%w; pass --destination /absolute/path", err))
	}
	self, err := os.Executable()
	if err != nil {
		fatal(err)
	}
	if err := install.Install(self); err != nil {
		fatal(err)
	}
	fmt.Printf("destination folder: %s\n", cfg.DestDir)
	fmt.Printf("receiver name: %s\n", cfg.DeviceName)
	fmt.Println("shotsync installed and started (launchd agent com.shotsync.receiver)")
	fmt.Println("Verify with: shotsync status")
}

func cmdConfigure(args []string) {
	fs := flag.NewFlagSet("configure", flag.ContinueOnError)
	fs.SetOutput(io.Discard)
	destination := fs.String("destination", "", "absolute folder where received media is stored")
	receiverName := fs.String("receiver-name", "", "privacy-safe receiver display name")
	if err := fs.Parse(args); err != nil || fs.NArg() != 0 || (*destination == "" && *receiverName == "") {
		fatal(fmt.Errorf("usage: shotsync configure [--destination /absolute/path] [--receiver-name \"Name\"]"))
	}
	cfg, err := config.Load()
	if err != nil {
		fatal(err)
	}
	if *destination != "" {
		if err := cfg.SetDestination(*destination); err != nil {
			fatal(err)
		}
	}
	if *receiverName != "" {
		if err := cfg.SetDeviceName(*receiverName); err != nil {
			fatal(err)
		}
	}
	fmt.Printf("configuration updated: %s\n", cfg.Path())
	if install.IsLoaded() {
		fmt.Println("The destination reloads automatically; restart the receiver after changing its name.")
	}
}

// --- pairing ---

type qrPayload struct {
	Version                int    `json:"version"`
	DeviceID               string `json:"device_id"`
	DeviceName             string `json:"device_name"`
	LocalHost              string `json:"local_host"`
	Port                   int    `json:"port"`
	CertificateFingerprint string `json:"certificate_fingerprint"`
	PairingSecret          string `json:"pairing_secret"`
}

func cmdGenQR() {
	payload, err := issuePairingLocally()
	if err != nil {
		fatal(err)
	}
	data, _ := json.Marshal(payload)
	text, err := qr.Text(string(data))
	if err != nil {
		fatal(err)
	}
	fmt.Println("Pairing QR code (valid for 5 minutes):")
	fmt.Println(text)
	fmt.Println("Scan it with the ShotSync Android app.")
	fmt.Printf("Payload: %s\n", data)
}

// issuePairingLocally starts a throwaway server state or reuses the running
// receiver: it prefers the running receiver so the secret is actually valid.
func issuePairingLocally() (*qrPayload, error) {
	cfg, err := config.Load()
	if err != nil {
		return nil, err
	}
	// Try the running receiver's admin endpoint first.
	client := adminClient()
	resp, err := client.Post(
		fmt.Sprintf("https://127.0.0.1:%d/api/v1/admin/pairing-issue", cfg.Port),
		"application/json", strings.NewReader("{}"))
	if err == nil {
		defer resp.Body.Close()
		var p qrPayload
		if err := json.NewDecoder(resp.Body).Decode(&p); err == nil && p.PairingSecret != "" {
			return &p, nil
		}
	}
	// Fall back: load identity directly and print a QR that works only if
	// the receiver restarts with this process; warn the user.
	dir, err := config.Dir()
	if err != nil {
		return nil, err
	}
	id, err := identity.LoadOrCreate(dir)
	if err != nil {
		return nil, err
	}
	host := identity.NetworkHost(id.DeviceID)
	secret, err := identity.NewPairingSecret()
	if err != nil {
		return nil, err
	}
	return &qrPayload{
		Version:                1,
		DeviceID:               id.DeviceID,
		DeviceName:             cfg.DeviceName,
		LocalHost:              host + ".local",
		Port:                   cfg.Port,
		CertificateFingerprint: id.CertFingerprint,
		PairingSecret:          secret,
	}, nil
}

// adminClient builds an HTTP client that trusts the receiver's own
// self-signed certificate (used only for loopback admin calls).
func adminClient() *http.Client {
	dir, _ := config.Dir()
	id, err := identity.LoadOrCreate(dir)
	if err != nil {
		return &http.Client{Timeout: 5 * time.Second}
	}
	roots := poolFromPEM(id.CertPEM)
	return &http.Client{
		Timeout: 5 * time.Second,
		Transport: &http.Transport{
			TLSClientConfig: &tls.Config{RootCAs: roots, MinVersion: tls.VersionTLS13},
		},
	}
}

// --- devices ---

func cmdDevices() {
	dir, err := config.Dir()
	if err != nil {
		fatal(err)
	}
	st, err := store.Open(dir)
	if err != nil {
		fatal(err)
	}
	devices := store.SortDevicesByName(st.DevicesSnapshot())
	if len(devices) == 0 {
		fmt.Println("No paired devices.")
		return
	}
	for _, d := range devices {
		fmt.Printf("%s\t%s\t(paired %s)\n", d.ID, d.Name, d.PairedAt.Format("2006-01-02 15:04"))
	}
}

func cmdUnpair(deviceID string) {
	dir, err := config.Dir()
	if err != nil {
		fatal(err)
	}
	st, err := store.Open(dir)
	if err != nil {
		fatal(err)
	}
	if st.GetDevice(deviceID) == nil {
		fatal(fmt.Errorf("no device with id %q", deviceID))
	}
	keychain.Delete(deviceID)
	if err := st.RemoveDevice(deviceID); err != nil {
		fatal(err)
	}
	fmt.Printf("device %s unpaired\n", deviceID)
}

// poolFromPEM builds a cert pool from PEM data.
func poolFromPEM(pemData []byte) *x509.CertPool {
	pool := x509.NewCertPool()
	pool.AppendCertsFromPEM(pemData)
	return pool
}

// writeQRFile renders the payload to a temporary PNG and returns its path.
func writeQRFile(payload *qrPayload) (string, error) {
	data, _ := json.Marshal(payload)
	png, err := qr.PNG(string(data), 512)
	if err != nil {
		return "", err
	}
	path := filepath.Join(os.TempDir(), "shotsync-pairing.png")
	if err := os.WriteFile(path, png, 0o600); err != nil {
		return "", err
	}
	return path, nil
}

var _ = io.Discard
