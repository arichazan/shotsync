package config

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
)

// Defaults tuned to the PRD.
const (
	DefaultPort       = 47831
	DefaultMaxSizeMB  = 500 // generous: covers long screen recordings
	DefaultDeviceName = "ShotSync Receiver"
)

// Config is the user-editable receiver configuration.
type Config struct {
	Port            int    `json:"port"`
	BindAddr        string `json:"bind_addr"`
	DeviceName      string `json:"device_name"`
	DestDir         string `json:"dest_dir"`
	MaxSizeMB       int64  `json:"max_size_mb"`
	Paused          bool   `json:"paused"`
	LogLevel        string `json:"log_level"`
	RateLimitPerMin int    `json:"rate_limit_per_min"`

	path string
	mu   sync.RWMutex
}

// Dir returns the per-user support directory (~/Library/Application Support/ShotSync).
// SHOTSYNC_HOME overrides the base for tests and portable installs.
func Dir() (string, error) {
	if override := os.Getenv("SHOTSYNC_HOME"); override != "" {
		return override, nil
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(home, "Library", "Application Support", "ShotSync"), nil
}

// Default returns privacy-safe defaults. DestDir intentionally has no default:
// the user must choose it during installation.
func Default() (*Config, error) {
	dir, err := Dir()
	if err != nil {
		return nil, err
	}
	return &Config{
		Port:            DefaultPort,
		BindAddr:        "0.0.0.0",
		DeviceName:      DefaultDeviceName,
		DestDir:         "",
		MaxSizeMB:       DefaultMaxSizeMB,
		Paused:          false,
		LogLevel:        "info",
		RateLimitPerMin: 300,
		path:            filepath.Join(dir, "config.json"),
	}, nil
}

// Load reads the config file, falling back to defaults.
func Load() (*Config, error) {
	c, err := Default()
	if err != nil {
		return nil, err
	}
	if err := os.MkdirAll(filepath.Dir(c.path), 0o700); err != nil {
		return nil, err
	}
	data, err := os.ReadFile(c.path)
	if err != nil {
		if os.IsNotExist(err) {
			return c, nil
		}
		return nil, err
	}
	// Unmarshal into a fresh default so new fields keep working values.
	if err := json.Unmarshal(data, c); err != nil {
		return nil, fmt.Errorf("parse %s: %w", c.path, err)
	}
	if c.Port <= 0 || c.Port > 65535 {
		c.Port = DefaultPort
	}
	if c.MaxSizeMB <= 0 {
		c.MaxSizeMB = DefaultMaxSizeMB
	}
	c.DestDir = strings.TrimSpace(c.DestDir)
	if strings.TrimSpace(c.DeviceName) == "" {
		c.DeviceName = DefaultDeviceName
	}
	if c.BindAddr == "" {
		c.BindAddr = "0.0.0.0"
	}
	if c.RateLimitPerMin <= 0 {
		c.RateLimitPerMin = 30
	}
	return c, nil
}

// Save atomically persists the configuration.
func (c *Config) Save() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	data, err := json.MarshalIndent(c, "", "  ")
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(c.path), 0o700); err != nil {
		return err
	}
	tmp := c.path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, c.path)
}

// Path returns the config file path.
func (c *Config) Path() string { return c.path }

// SetPaused updates the paused flag and persists.
func (c *Config) SetPaused(paused bool) error {
	c.mu.Lock()
	c.Paused = paused
	c.mu.Unlock()
	return c.Save()
}

// SetDestination validates, creates, and persists a user-selected destination.
func (c *Config) SetDestination(path string) error {
	clean, err := ValidateDestination(path)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(clean, 0o755); err != nil {
		return fmt.Errorf("create destination: %w", err)
	}
	c.mu.Lock()
	c.DestDir = clean
	c.mu.Unlock()
	return c.Save()
}

// SetDeviceName persists the privacy-safe receiver display name chosen by the user.
func (c *Config) SetDeviceName(name string) error {
	name = strings.TrimSpace(name)
	if name == "" {
		return fmt.Errorf("receiver name is required")
	}
	if len(name) > 63 {
		return fmt.Errorf("receiver name must be at most 63 characters")
	}
	c.mu.Lock()
	c.DeviceName = name
	c.mu.Unlock()
	return c.Save()
}

// ValidateDestination requires an absolute path and rejects the filesystem root.
func ValidateDestination(path string) (string, error) {
	path = strings.TrimSpace(path)
	if path == "" {
		return "", fmt.Errorf("destination folder is required")
	}
	clean := filepath.Clean(path)
	if !filepath.IsAbs(clean) {
		return "", fmt.Errorf("destination folder must be an absolute path")
	}
	if clean == string(filepath.Separator) {
		return "", fmt.Errorf("filesystem root cannot be used as the destination")
	}
	return clean, nil
}

// Reload re-reads the config file into this Config, preserving the path.
// Fields changed on disk take effect immediately (except port/bind, which
// require a restart).
func (c *Config) Reload() error {
	fresh, err := Load()
	if err != nil {
		return err
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	c.DeviceName = fresh.DeviceName
	c.DestDir = fresh.DestDir
	c.MaxSizeMB = fresh.MaxSizeMB
	c.Paused = fresh.Paused
	c.LogLevel = fresh.LogLevel
	c.RateLimitPerMin = fresh.RateLimitPerMin
	return nil
}
