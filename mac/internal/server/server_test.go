package server

import (
	"bytes"
	"crypto/sha256"
	"crypto/tls"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/arichazan/shotsync/mac/internal/config"
	"github.com/arichazan/shotsync/mac/internal/identity"
	"github.com/arichazan/shotsync/mac/internal/store"
)

type memTokens struct {
	mu sync.Mutex
	m  map[string]string
}

func newMemTokens() *memTokens { return &memTokens{m: map[string]string{}} }
func (t *memTokens) Get(a string) (string, bool) {
	t.mu.Lock()
	defer t.mu.Unlock()
	v, ok := t.m[a]
	return v, ok
}
func (t *memTokens) Set(a, v string) error {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.m[a] = v
	return nil
}
func (t *memTokens) Delete(a string) {
	t.mu.Lock()
	defer t.mu.Unlock()
	delete(t.m, a)
}

// testEnv bundles a running test receiver.
type testEnv struct {
	srv      *Server
	ts       *httptest.Server
	destDir  string
	stateDir string
	tokens   *memTokens
	cfg      *config.Config
}

func newTestEnv(t *testing.T) *testEnv {
	t.Helper()
	base := t.TempDir()
	stateDir := filepath.Join(base, "state")
	destDir := filepath.Join(base, "dest")
	_ = os.MkdirAll(stateDir, 0o700)

	id, err := identity.LoadOrCreate(stateDir)
	if err != nil {
		t.Fatal(err)
	}
	st, err := store.Open(stateDir)
	if err != nil {
		t.Fatal(err)
	}
	cfg := &config.Config{
		Port:       47831,
		BindAddr:   "127.0.0.1",
		DeviceName: "Test Mac",
		DestDir:    destDir,
		MaxSizeMB:  2,
		LogLevel:   "error",
	}
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	srv := New(cfg, id, st, newMemTokens(), logger)
	ts := httptest.NewUnstartedServer(srv.Handler())
	ts.TLS = &tls.Config{Certificates: []tls.Certificate{id.TLSCert}, MinVersion: tls.VersionTLS13}
	ts.StartTLS()
	t.Cleanup(ts.Close)
	return &testEnv{srv: srv, ts: ts, destDir: destDir, stateDir: stateDir, tokens: newMemTokens(), cfg: cfg}
}

// pair pairs a device and returns its token.
func (e *testEnv) pair(t *testing.T) (deviceID, token string) {
	t.Helper()
	secret, err := e.srv.IssuePairingSecret()
	if err != nil {
		t.Fatal(err)
	}
	body, _ := json.Marshal(map[string]string{
		"pairing_secret": secret,
		"device_id":      "test-device-0001",
		"device_name":    "Pixel Test",
	})
	resp, err := e.ts.Client().Post(e.ts.URL+"/api/v1/pair", "application/json", bytes.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("pair status = %d", resp.StatusCode)
	}
	var out struct {
		DeviceToken string `json:"device_token"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		t.Fatal(err)
	}
	return "test-device-0001", out.DeviceToken
}

func (e *testEnv) upload(t *testing.T, token, filename string, content []byte, contentType string, hdr map[string]string) *http.Response {
	t.Helper()
	hash := sha256.Sum256(content)
	h := map[string]string{
		"Authorization":        "Bearer " + token,
		"X-ShotSync-Device-ID": "test-device-0001",
		"X-ShotSync-File-ID":   "11111111-2222-3333-4444-555555555555",
		"X-ShotSync-SHA256":    hex.EncodeToString(hash[:]),
		"X-ShotSync-Filename":  filename,
		"Content-Type":         contentType,
	}
	for k, v := range hdr {
		h[k] = v
	}
	req, err := http.NewRequest(http.MethodPost, e.ts.URL+"/api/v1/screenshots", bytes.NewReader(content))
	if err != nil {
		t.Fatal(err)
	}
	for k, v := range h {
		req.Header.Set(k, v)
	}
	resp, err := e.ts.Client().Do(req)
	if err != nil {
		t.Fatal(err)
	}
	return resp
}

func decodeUploadResponse(t *testing.T, resp *http.Response) map[string]any {
	t.Helper()
	defer resp.Body.Close()
	var out map[string]any
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	return out
}

// --- pairing ---

func TestPairingFlow(t *testing.T) {
	e := newTestEnv(t)

	secret, err := e.srv.IssuePairingSecret()
	if err != nil {
		t.Fatal(err)
	}

	// Wrong secret rejected.
	bad := map[string]string{"pairing_secret": "wrong", "device_id": "test-device-0001", "device_name": "P"}
	b, _ := json.Marshal(bad)
	resp, err := e.ts.Client().Post(e.ts.URL+"/api/v1/pair", "application/json", bytes.NewReader(b))
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("wrong secret status = %d, want 401", resp.StatusCode)
	}

	// Correct secret pairs.
	good := map[string]string{"pairing_secret": secret, "device_id": "test-device-0001", "device_name": "Pixel"}
	b, _ = json.Marshal(good)
	resp, err = e.ts.Client().Post(e.ts.URL+"/api/v1/pair", "application/json", bytes.NewReader(b))
	if err != nil {
		t.Fatal(err)
	}
	var out struct {
		Status      string `json:"status"`
		DeviceToken string `json:"device_token"`
	}
	_ = json.NewDecoder(resp.Body).Decode(&out)
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK || out.DeviceToken == "" {
		t.Fatalf("pair status=%d token=%q", resp.StatusCode, out.DeviceToken)
	}

	// Secret is one-time: reuse fails.
	resp, err = e.ts.Client().Post(e.ts.URL+"/api/v1/pair", "application/json", bytes.NewReader(b))
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("reused secret status = %d, want 401", resp.StatusCode)
	}
}

func TestPairingSecretExpiry(t *testing.T) {
	e := newTestEnv(t)
	e.srv.pairing = NewPairing(10 * time.Millisecond)
	secret, _ := e.srv.IssuePairingSecret()
	time.Sleep(30 * time.Millisecond)
	body, _ := json.Marshal(map[string]string{"pairing_secret": secret, "device_id": "dev-1", "device_name": "P"})
	resp, err := e.ts.Client().Post(e.ts.URL+"/api/v1/pair", "application/json", bytes.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("expired secret status = %d, want 401", resp.StatusCode)
	}
}

// --- authentication ---

func TestUploadRequiresAuth(t *testing.T) {
	e := newTestEnv(t)
	content := []byte("fake-png-bytes")
	hash := sha256.Sum256(content)
	req, _ := http.NewRequest(http.MethodPost, e.ts.URL+"/api/v1/screenshots", bytes.NewReader(content))
	req.Header.Set("X-ShotSync-Device-ID", "unknown-device")
	req.Header.Set("X-ShotSync-File-ID", "11111111-2222-3333-4444-555555555555")
	req.Header.Set("X-ShotSync-SHA256", hex.EncodeToString(hash[:]))
	req.Header.Set("X-ShotSync-Filename", "s.png")
	req.Header.Set("Content-Type", "image/png")
	resp, err := e.ts.Client().Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("unauthenticated upload status = %d, want 401", resp.StatusCode)
	}
}

func TestUploadWrongTokenRejected(t *testing.T) {
	e := newTestEnv(t)
	e.pair(t)
	resp := e.upload(t, "00"+"bad", "s.png", []byte("data"), "image/png", nil)
	resp.Body.Close()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("bad token status = %d, want 401", resp.StatusCode)
	}
}

func TestRevokedTokenRejected(t *testing.T) {
	e := newTestEnv(t)
	deviceID, token := e.pair(t)
	req, _ := http.NewRequest(http.MethodDelete, e.ts.URL+"/api/v1/device/"+deviceID, nil)
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("X-ShotSync-Device-ID", deviceID)
	resp, err := e.ts.Client().Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("revoke status = %d", resp.StatusCode)
	}
	up := e.upload(t, token, "s.png", []byte("data"), "image/png", nil)
	up.Body.Close()
	if up.StatusCode != http.StatusUnauthorized {
		t.Fatalf("upload after revoke status = %d, want 401", up.StatusCode)
	}
}

// --- uploads ---

func TestUploadStoresFile(t *testing.T) {
	e := newTestEnv(t)
	_, token := e.pair(t)
	content := []byte("\x89PNG\r\n\x1a\nfake-content")
	captured := "2026-08-14T09:45:21+02:00"
	resp := e.upload(t, token, "Screenshot_2026-08-14_09-45-21.png", content, "image/png",
		map[string]string{"X-ShotSync-Captured-At": captured})
	out := decodeUploadResponse(t, resp)
	if resp.StatusCode != http.StatusOK || out["status"] != "stored" {
		t.Fatalf("upload failed: status=%d body=%v", resp.StatusCode, out)
	}
	path := filepath.Join(e.destDir, "Screenshot_2026-08-14_09-45-21.png")
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(data, content) {
		t.Fatal("stored content mismatch")
	}
	// Capture timestamp preserved.
	fi, _ := os.Stat(path)
	want, _ := time.Parse(time.RFC3339, captured)
	if !fi.ModTime().Equal(want) {
		t.Fatalf("mtime = %v, want %v", fi.ModTime(), want)
	}
	// No temp files left behind.
	entries, _ := os.ReadDir(e.destDir)
	for _, ent := range entries {
		if strings.HasPrefix(ent.Name(), ".shotsync-tmp-") {
			t.Fatalf("leftover temp file %s", ent.Name())
		}
	}
}

func TestDuplicateByHash(t *testing.T) {
	e := newTestEnv(t)
	_, token := e.pair(t)
	content := []byte("identical-bytes-1")
	r1 := e.upload(t, token, "a.png", content, "image/png", nil)
	o1 := decodeUploadResponse(t, r1)
	if o1["status"] != "stored" {
		t.Fatalf("first upload = %v", o1)
	}
	r2 := e.upload(t, token, "b.png", content, "image/png", nil)
	o2 := decodeUploadResponse(t, r2)
	if r2.StatusCode != http.StatusOK || o2["status"] != "already_exists" {
		t.Fatalf("duplicate upload = %v", o2)
	}
	entries, _ := os.ReadDir(e.destDir)
	files := 0
	for _, ent := range entries {
		if !strings.HasPrefix(ent.Name(), ".shotsync-tmp-") {
			files++
		}
	}
	if files != 1 {
		t.Fatalf("expected exactly 1 stored file, got %d", files)
	}
}

func TestFilenameCollisionGetsSuffix(t *testing.T) {
	e := newTestEnv(t)
	_, token := e.pair(t)
	r1 := e.upload(t, token, "Screenshot_x.png", []byte("content-A"), "image/png", nil)
	o1 := decodeUploadResponse(t, r1)
	if o1["status"] != "stored" {
		t.Fatal("first upload failed")
	}
	r2 := e.upload(t, token, "Screenshot_x.png", []byte("content-B-different"), "image/png", nil)
	o2 := decodeUploadResponse(t, r2)
	if o2["status"] != "stored" || o2["filename"] != "Screenshot_x_2.png" {
		t.Fatalf("collision result = %v", o2)
	}
	// Three-way collision.
	r3 := e.upload(t, token, "Screenshot_x.png", []byte("content-C-third"), "image/png", nil)
	o3 := decodeUploadResponse(t, r3)
	if o3["filename"] != "Screenshot_x_3.png" {
		t.Fatalf("third collision = %v", o3)
	}
}

func TestHashMismatchRejected(t *testing.T) {
	e := newTestEnv(t)
	_, token := e.pair(t)
	content := []byte("actual-content")
	resp := e.upload(t, token, "x.png", content, "image/png",
		map[string]string{"X-ShotSync-SHA256": strings.Repeat("0", 64)})
	out := decodeUploadResponse(t, resp)
	if resp.StatusCode != http.StatusBadRequest || out["error"] != "hash_mismatch" {
		t.Fatalf("mismatch result: %d %v", resp.StatusCode, out)
	}
	entries, _ := os.ReadDir(e.destDir)
	if len(entries) != 0 {
		t.Fatalf("mismatched upload left files: %v", entries)
	}
}

func TestSizeLimitRejected(t *testing.T) {
	e := newTestEnv(t)
	e.cfg.MaxSizeMB = 1
	_, token := e.pair(t)
	content := make([]byte, 1024*1024+100)
	resp := e.upload(t, token, "big.png", content, "image/png", nil)
	out := decodeUploadResponse(t, resp)
	if resp.StatusCode != http.StatusRequestEntityTooLarge || out["error"] != "too_large" {
		t.Fatalf("oversize result: %d %v", resp.StatusCode, out)
	}
}

func TestUnsupportedMediaRejected(t *testing.T) {
	e := newTestEnv(t)
	_, token := e.pair(t)
	resp := e.upload(t, token, "evil.sh", []byte("#!/bin/sh"), "text/plain", nil)
	resp.Body.Close()
	if resp.StatusCode != http.StatusUnsupportedMediaType {
		t.Fatalf("text/plain status = %d, want 415", resp.StatusCode)
	}
}

func TestVideoUploadAccepted(t *testing.T) {
	e := newTestEnv(t)
	_, token := e.pair(t)
	for _, c := range []struct {
		file, mime string
		content    []byte
	}{
		{"Screen_Recording_20260814_123456.mp4", "video/mp4",
			append([]byte("\x00\x00\x00\x18ftypmp42"), make([]byte, 50000)...)},
		{"screen-recording.webm", "video/webm",
			append([]byte("\x1a\x45\xdf\xa3"), make([]byte, 60000)...)},
	} {
		resp := e.upload(t, token, c.file, c.content, c.mime, nil)
		out := decodeUploadResponse(t, resp)
		if out["status"] != "stored" {
			t.Fatalf("%s upload = %v", c.file, out)
		}
	}
}

func TestVideoExtensionMismatchRejected(t *testing.T) {
	e := newTestEnv(t)
	_, token := e.pair(t)
	resp := e.upload(t, token, "recording.mp4", []byte("data"), "image/png", nil)
	resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("mp4 with image/png status = %d, want 400", resp.StatusCode)
	}
}

func TestInterruptedUploadLeavesNoFile(t *testing.T) {
	e := newTestEnv(t)
	_, token := e.pair(t)
	content := bytes.Repeat([]byte("x"), 100000)
	hash := sha256.Sum256(content)

	// Simulate a network interruption: declare Content-Length 100000 but
	// send only half the body, then drop the connection.
	addr := strings.TrimPrefix(e.ts.URL, "https://")
	conn, err := tls.Dial("tcp", addr, &tls.Config{InsecureSkipVerify: true, MinVersion: tls.VersionTLS13})
	if err != nil {
		t.Fatal(err)
	}
	req := fmt.Sprintf(
		"POST /api/v1/screenshots HTTP/1.1\r\nHost: %s\r\nAuthorization: Bearer %s\r\nX-ShotSync-Device-ID: test-device-0001\r\nX-ShotSync-File-ID: 22222222-3333-4444-5555-666666666666\r\nX-ShotSync-SHA256: %s\r\nX-ShotSync-Filename: interrupted.png\r\nContent-Type: image/png\r\nContent-Length: 100000\r\n\r\n",
		addr, token, hex.EncodeToString(hash[:]))
	conn.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err := conn.Write([]byte(req)); err != nil {
		t.Fatal(err)
	}
	if _, err := conn.Write(content[:50000]); err != nil {
		t.Fatal(err)
	}
	conn.Close()

	// Give the server a moment to process the truncated body.
	time.Sleep(300 * time.Millisecond)
	entries, _ := os.ReadDir(e.destDir)
	if len(entries) != 0 {
		t.Fatalf("interrupted upload left files: %v", entries)
	}
}

func TestPausedReceiverRejectsUploads(t *testing.T) {
	e := newTestEnv(t)
	_, token := e.pair(t)
	e.cfg.Paused = true
	resp := e.upload(t, token, "x.png", []byte("data"), "image/png", nil)
	out := decodeUploadResponse(t, resp)
	if resp.StatusCode != http.StatusServiceUnavailable || out["error"] != "paused" {
		t.Fatalf("paused result: %d %v", resp.StatusCode, out)
	}
	e.cfg.Paused = false
	resp2 := e.upload(t, token, "x.png", []byte("data"), "image/png", nil)
	o2 := decodeUploadResponse(t, resp2)
	if o2["status"] != "stored" {
		t.Fatalf("upload after unpause = %v", o2)
	}
}

func TestHashKnownButFileMissingRestores(t *testing.T) {
	e := newTestEnv(t)
	_, token := e.pair(t)
	content := []byte("restore-me")
	r1 := e.upload(t, token, "gone.png", content, "image/png", nil)
	decodeUploadResponse(t, r1)
	if err := os.Remove(filepath.Join(e.destDir, "gone.png")); err != nil {
		t.Fatal(err)
	}
	r2 := e.upload(t, token, "gone.png", content, "image/png", nil)
	o2 := decodeUploadResponse(t, r2)
	if o2["status"] != "stored" {
		t.Fatalf("re-store result = %v", o2)
	}
}

func TestHealthEndpoint(t *testing.T) {
	e := newTestEnv(t)
	resp, err := e.ts.Client().Get(e.ts.URL + "/api/v1/health")
	if err != nil {
		t.Fatal(err)
	}
	var out map[string]any
	_ = json.NewDecoder(resp.Body).Decode(&out)
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK || out["status"] != "ok" || out["protocol_version"] != float64(1) {
		t.Fatalf("health = %d %v", resp.StatusCode, out)
	}
	raw, _ := io.ReadAll(resp.Body)
	if strings.Contains(string(raw), "token") || strings.Contains(string(raw), "key") {
		t.Fatal("health endpoint leaks credentials")
	}
	_ = fmt.Sprintf("")
}
