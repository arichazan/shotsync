// Package store implements the receiver's compact local state: paired
// devices, completed transfers (for hash deduplication) and history.
// Tokens themselves live in the macOS Keychain; only hashes are kept here.
package store

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"
)

// Device records a paired Android device.
type Device struct {
	ID        string    `json:"id"`
	Name      string    `json:"name"`
	PairedAt  time.Time `json:"paired_at"`
	TokenHash string    `json:"token_hash"`
}

// Transfer is one completed (or deduplicated) upload record.
type Transfer struct {
	FileID   string    `json:"file_id"`
	SHA256   string    `json:"sha256"`
	Filename string    `json:"filename"`
	DeviceID string    `json:"device_id"`
	Size     int64     `json:"size"`
	StoredAt time.Time `json:"stored_at"`
}

// State is the persisted store state.
type State struct {
	Version   int                `json:"version"`
	Devices   map[string]*Device `json:"devices"`
	Transfers []*Transfer        `json:"transfers"`

	mu   sync.RWMutex `json:"-"`
	path string       `json:"-"`
}

// Open loads the state file, creating an empty store if missing.
func Open(dir string) (*State, error) {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, err
	}
	s := &State{
		Version: 1,
		Devices: map[string]*Device{},
		path:    filepath.Join(dir, "state.json"),
	}
	data, err := os.ReadFile(s.path)
	if err != nil {
		if os.IsNotExist(err) {
			return s, nil
		}
		return nil, err
	}
	if err := json.Unmarshal(data, s); err != nil {
		return nil, fmt.Errorf("corrupt state file %s: %w", s.path, err)
	}
	if s.Devices == nil {
		s.Devices = map[string]*Device{}
	}
	return s, nil
}

// save atomically persists state.
func (s *State) save() error {
	data, err := json.MarshalIndent(s, "", "  ")
	if err != nil {
		return err
	}
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, s.path)
}

// AddDevice stores a paired device and its token hash.
func (s *State) AddDevice(id, name, token string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	sum := sha256.Sum256([]byte(token))
	s.Devices[id] = &Device{
		ID:        id,
		Name:      name,
		PairedAt:  time.Now(),
		TokenHash: hex.EncodeToString(sum[:]),
	}
	return s.save()
}

// RemoveDevice unpairs a device.
func (s *State) RemoveDevice(id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.Devices, id)
	return s.save()
}

// GetDevice returns the device record or nil.
func (s *State) GetDevice(id string) *Device {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.Devices[id]
}

// DevicesSnapshot returns a copy of the device map.
func (s *State) DevicesSnapshot() map[string]*Device {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := make(map[string]*Device, len(s.Devices))
	for k, v := range s.Devices {
		cp := *v
		out[k] = &cp
	}
	return out
}

// FindBySHA256 returns the transfer with the given content hash, if any.
func (s *State) FindBySHA256(hash string) *Transfer {
	s.mu.RLock()
	defer s.mu.RUnlock()
	for _, t := range s.Transfers {
		if t.SHA256 == hash {
			cp := *t
			return &cp
		}
	}
	return nil
}

// RecordTransfer appends a transfer and trims history to the newest cap.
func (s *State) RecordTransfer(t *Transfer, cap int) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	// Replace any existing record with the same hash to keep history unique.
	kept := s.Transfers[:0]
	for _, old := range s.Transfers {
		if old.SHA256 != t.SHA256 {
			kept = append(kept, old)
		}
	}
	kept = append(kept, t)
	if cap <= 0 {
		cap = 10000
	}
	if len(kept) > cap {
		kept = kept[len(kept)-cap:]
	}
	s.Transfers = kept
	return s.save()
}

// RecentTransfers returns the most recent n transfers, newest first.
func (s *State) RecentTransfers(n int) []*Transfer {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := make([]*Transfer, 0, n)
	for i := len(s.Transfers) - 1; i >= 0 && len(out) < n; i-- {
		cp := *s.Transfers[i]
		out = append(out, &cp)
	}
	return out
}

// TrimTransfers removes completed-transfer records older than retention.
func (s *State) TrimTransfers(retention time.Duration) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	cutoff := time.Now().Add(-retention)
	kept := s.Transfers[:0]
	for _, t := range s.Transfers {
		if t.StoredAt.After(cutoff) {
			kept = append(kept, t)
		}
	}
	s.Transfers = kept
	return s.save()
}

// Path exposes the state file location.
func (s *State) Path() string { return s.path }

// SortDevicesByName returns device IDs sorted by name (stable for QR lists).
func SortDevicesByName(devices map[string]*Device) []*Device {
	out := make([]*Device, 0, len(devices))
	for _, d := range devices {
		cp := *d
		out = append(out, &cp)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Name < out[j].Name })
	return out
}
