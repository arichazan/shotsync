// Package server implements the ShotSync HTTP receiver: authenticated
// screenshot uploads, pairing, health and status endpoints, rate limiting
// and atomic file storage.
package server

import (
	"context"
	"crypto/subtle"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/arichazan/shotsync/mac/internal/config"
	"github.com/arichazan/shotsync/mac/internal/identity"
	"github.com/arichazan/shotsync/mac/internal/store"
)

// TokenStore abstracts token persistence (Keychain in production, memory in tests).
type TokenStore interface {
	Get(account string) (string, bool)
	Set(account, token string) error
	Delete(account string)
}

// Notifier receives an event when a new screenshot or recording is stored.
type Notifier func(deviceID, filename, contentType string)

// Pairing holds the active one-time pairing secret.
type Pairing struct {
	mu        sync.Mutex
	secret    string
	expiresAt time.Time
	ttl       time.Duration
}

// NewPairing creates an empty pairing state.
func NewPairing(ttl time.Duration) *Pairing {
	if ttl <= 0 {
		ttl = 5 * time.Minute
	}
	return &Pairing{ttl: ttl}
}

// Issue generates a new pairing secret, replacing any previous one.
func (p *Pairing) Issue() (string, error) {
	secret, err := identity.NewPairingSecret()
	if err != nil {
		return "", err
	}
	p.mu.Lock()
	p.secret = secret
	p.expiresAt = time.Now().Add(p.ttl)
	p.mu.Unlock()
	return secret, nil
}

// Expiry returns the active secret's expiry time.
func (p *Pairing) Expiry() time.Time {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.expiresAt
}

// Consume verifies a presented secret in constant time and invalidates it
// on success. It returns ErrPairingInvalid if the secret is wrong or expired.
func (p *Pairing) Consume(presented string) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.secret == "" || time.Now().After(p.expiresAt) {
		return ErrPairingExpired
	}
	if subtle.ConstantTimeCompare([]byte(p.secret), []byte(presented)) != 1 {
		return ErrPairingInvalid
	}
	p.secret = ""
	p.expiresAt = time.Time{}
	return nil
}

// Server is the ShotSync receiver.
type Server struct {
	cfg       *config.Config
	id        *identity.Identity
	state     *store.State
	tokens    TokenStore
	pairing   *Pairing
	log       *slog.Logger
	notify    Notifier
	limiter   *bucket
	lastSeen  map[string]time.Time
	lastSeenM sync.Mutex
}

// New constructs a receiver. Logger may be nil (falls back to stdout).
func New(cfg *config.Config, id *identity.Identity, st *store.State, tokens TokenStore, log *slog.Logger) *Server {
	if log == nil {
		log = slog.New(slog.NewTextHandler(os.Stderr, nil))
	}
	rate := cfg.RateLimitPerMin
	if rate <= 0 {
		rate = 300
	}
	return &Server{
		cfg:      cfg,
		id:       id,
		state:    st,
		tokens:   tokens,
		pairing:  NewPairing(5 * time.Minute),
		log:      log,
		limiter:  newBucket(float64(rate), float64(rate)/2), // burst = half the per-minute rate
		lastSeen: map[string]time.Time{},
	}
}

// SetNotifier installs the notification callback.
func (s *Server) SetNotifier(n Notifier) { s.notify = n }

// Pairing returns the pairing controller.
func (s *Server) Pairing() *Pairing { return s.pairing }

// IssuePairingSecret generates a fresh pairing secret (also useful for QR).
func (s *Server) IssuePairingSecret() (string, error) { return s.pairing.Issue() }

// Handler builds the HTTP mux.
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/v1/health", s.handleHealth)
	mux.HandleFunc("POST /api/v1/pair", s.handlePair)
	mux.HandleFunc("POST /api/v1/screenshots", s.requireAuth(s.handleUpload))
	mux.HandleFunc("DELETE /api/v1/device/{id}", s.requireAuth(s.handleRevoke))
	mux.HandleFunc("GET /api/v1/status", s.loopbackOnly(s.handleStatus))
	mux.HandleFunc("POST /api/v1/admin/pause", s.loopbackOnly(s.handleAdminPause))
	mux.HandleFunc("POST /api/v1/admin/pairing-issue", s.loopbackOnly(s.handleAdminPairingIssue))
	return logRequests(s.log, mux)
}

// loopbackOnly restricts admin endpoints to local callers.
func (s *Server) loopbackOnly(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		host, _, err := net.SplitHostPort(r.RemoteAddr)
		if err != nil || !net.ParseIP(host).IsLoopback() {
			writeError(w, http.StatusForbidden, "forbidden", "loopback only")
			return
		}
		next(w, r)
	}
}

func (s *Server) handleAdminPause(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Paused bool `json:"paused"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1024)).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "bad_request", "invalid JSON body")
		return
	}
	if err := s.cfg.SetPaused(req.Paused); err != nil {
		writeError(w, http.StatusInternalServerError, "internal", "could not update config")
		return
	}
	s.log.Info("receiver pause toggled", "paused", req.Paused)
	writeJSON(w, http.StatusOK, map[string]any{"status": "ok", "paused": req.Paused})
}

// handleAdminPairingIssue mints a fresh one-time pairing secret and returns
// the QR payload. The secret expires after five minutes.
func (s *Server) handleAdminPairingIssue(w http.ResponseWriter, r *http.Request) {
	secret, err := s.pairing.Issue()
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal", "could not generate pairing secret")
		return
	}
	host := identity.NetworkHost(s.id.DeviceID)
	payload := map[string]any{
		"version":                 1,
		"device_id":               s.id.DeviceID,
		"device_name":             s.cfg.DeviceName,
		"local_host":              host + ".local",
		"port":                    s.cfg.Port,
		"certificate_fingerprint": s.id.CertFingerprint,
		"pairing_secret":          secret,
	}
	s.log.Info("pairing secret issued", "expires_in", "5m")
	writeJSON(w, http.StatusOK, payload)
}

// ListenAndServe runs the TLS receiver until ctx is cancelled.
func (s *Server) ListenAndServe(ctx context.Context, addr string) error {
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		return err
	}
	defer ln.Close()
	s.log.Info("receiver listening", "addr", ln.Addr().String(), "device_id", s.id.DeviceID)

	tlsCfg := &tls.Config{
		Certificates: []tls.Certificate{s.id.TLSCert},
		MinVersion:   tls.VersionTLS13,
	}
	httpServer := &http.Server{
		Handler:           s.Handler(),
		TLSConfig:         tlsCfg,
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       120 * time.Second,
	}
	go func() {
		<-ctx.Done()
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = httpServer.Shutdown(shutdownCtx)
	}()
	err = httpServer.ServeTLS(ln, "", "")
	if errors.Is(err, http.ErrServerClosed) {
		return nil
	}
	return err
}

// --- handlers ---

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	status := "ok"
	if s.cfg.Paused {
		status = "paused"
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"status":           status,
		"protocol_version": 1,
		"device_name":      s.cfg.DeviceName,
	})
}

type pairRequest struct {
	PairingSecret string `json:"pairing_secret"`
	DeviceID      string `json:"device_id"`
	DeviceName    string `json:"device_name"`
}

func (s *Server) handlePair(w http.ResponseWriter, r *http.Request) {
	var req pairRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "bad_request", "invalid JSON body")
		return
	}
	if err := s.pairing.Consume(req.PairingSecret); err != nil {
		s.log.Warn("pairing rejected", "reason", err.Error())
		writeError(w, http.StatusUnauthorized, "pairing_failed", "pairing secret invalid or expired")
		return
	}
	if len(req.DeviceID) < 8 || len(req.DeviceID) > 64 {
		writeError(w, http.StatusBadRequest, "bad_request", "invalid device_id")
		return
	}
	token, err := identity.NewDeviceToken()
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal", "token generation failed")
		return
	}
	name := strings.TrimSpace(req.DeviceName)
	if name == "" {
		name = req.DeviceID
	}
	if err := s.tokens.Set(req.DeviceID, token); err != nil {
		s.log.Error("keychain store failed", "err", err)
		writeError(w, http.StatusInternalServerError, "internal", "could not store token")
		return
	}
	if err := s.state.AddDevice(req.DeviceID, name, token); err != nil {
		writeError(w, http.StatusInternalServerError, "internal", "could not persist device")
		return
	}
	s.log.Info("device paired", "device_id", req.DeviceID, "device_name", name)
	writeJSON(w, http.StatusOK, map[string]any{
		"status":       "paired",
		"device_token": token,
	})
}

// requireAuth enforces Bearer-token authentication.
func (s *Server) requireAuth(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		token, ok := bearerToken(r)
		if !ok {
			writeError(w, http.StatusUnauthorized, "unauthorized", "missing bearer token")
			return
		}
		deviceID := strings.TrimSpace(r.Header.Get("X-ShotSync-Device-ID"))
		if deviceID == "" {
			writeError(w, http.StatusUnauthorized, "unauthorized", "missing device id")
			return
		}
		dev := s.state.GetDevice(deviceID)
		if dev == nil {
			writeError(w, http.StatusUnauthorized, "unauthorized", "unknown device")
			return
		}
		stored, ok := s.tokens.Get(deviceID)
		if !ok {
			writeError(w, http.StatusUnauthorized, "unauthorized", "token unavailable")
			return
		}
		if subtle.ConstantTimeCompare([]byte(stored), []byte(token)) != 1 {
			s.log.Warn("auth rejected", "device_id", deviceID)
			writeError(w, http.StatusUnauthorized, "unauthorized", "invalid token")
			return
		}
		s.touch(deviceID)
		next(w, r)
	}
}

func (s *Server) handleRevoke(w http.ResponseWriter, r *http.Request) {
	deviceID := r.PathValue("id")
	if deviceID == "" {
		writeError(w, http.StatusBadRequest, "bad_request", "missing device id")
		return
	}
	s.tokens.Delete(deviceID)
	if err := s.state.RemoveDevice(deviceID); err != nil {
		writeError(w, http.StatusInternalServerError, "internal", "could not remove device")
		return
	}
	s.log.Info("device unpaired", "device_id", deviceID)
	writeJSON(w, http.StatusOK, map[string]string{"status": "revoked"})
}

// handleStatus is a localhost-only admin endpoint used by the menu bar.
func (s *Server) handleStatus(w http.ResponseWriter, r *http.Request) {
	devices := make([]map[string]any, 0)
	s.lastSeenM.Lock()
	for id, d := range s.state.DevicesSnapshot() {
		entry := map[string]any{"device_id": id, "name": d.Name, "paired_at": d.PairedAt}
		if seen, ok := s.lastSeen[id]; ok {
			entry["last_seen"] = seen
		}
		devices = append(devices, entry)
	}
	s.lastSeenM.Unlock()
	last := s.state.RecentTransfers(1)
	var lastRecv any
	if len(last) > 0 {
		lastRecv = map[string]any{
			"filename":  last[0].Filename,
			"device_id": last[0].DeviceID,
			"stored_at": last[0].StoredAt,
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"status":           "ok",
		"paused":           s.cfg.Paused,
		"device_name":      s.cfg.DeviceName,
		"device_id":        s.id.DeviceID,
		"cert_fingerprint": s.id.CertFingerprint,
		"port":             s.cfg.Port,
		"devices":          devices,
		"last_received":    lastRecv,
	})
}

// --- helpers ---

func bearerToken(r *http.Request) (string, bool) {
	h := r.Header.Get("Authorization")
	const prefix = "Bearer "
	if len(h) > len(prefix) && strings.EqualFold(h[:len(prefix)], prefix) {
		return strings.TrimSpace(h[len(prefix):]), true
	}
	return "", false
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}

func writeError(w http.ResponseWriter, status int, code, msg string) {
	writeJSON(w, status, map[string]any{"status": "error", "error": code, "message": msg})
}

func (s *Server) touch(deviceID string) {
	s.lastSeenM.Lock()
	s.lastSeen[deviceID] = time.Now()
	s.lastSeenM.Unlock()
}

// logRequests emits one structured line per request with only safe fields.
func logRequests(log *slog.Logger, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		rec := &statusRecorder{ResponseWriter: w, status: 200}
		next.ServeHTTP(rec, r)
		attrs := []any{
			"method", r.Method,
			"path", r.URL.Path,
			"status", rec.status,
			"duration_ms", time.Since(start).Milliseconds(),
			"remote", remoteAddr(r),
		}
		if id := r.Header.Get("X-ShotSync-Device-ID"); id != "" && r.URL.Path != "/api/v1/status" {
			attrs = append(attrs, "device_id", id)
		}
		log.Info("request", attrs...)
	})
}

type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (r *statusRecorder) WriteHeader(code int) {
	r.status = code
	r.ResponseWriter.WriteHeader(code)
}

func remoteAddr(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

var _ = fmt.Sprintf
