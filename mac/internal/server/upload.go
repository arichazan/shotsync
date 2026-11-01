package server

import (
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	"github.com/arichazan/shotsync/mac/internal/store"
)

var (
	// allowedMIME restricts uploads to expected image/video types.
	allowedMIME = map[string]bool{
		"image/png":  true,
		"image/jpeg": true,
		"image/webp": true,
		"image/heic": true,
		"image/heif": true,
		"image/gif":  true,
		// Screen recordings.
		"video/mp4":       true,
		"video/webm":      true,
		"video/quicktime": true,
	}
	sha256Re = regexp.MustCompile(`^[0-9a-fA-F]{64}$`)
	uuidRe   = regexp.MustCompile(`^[0-9a-fA-F-]{8,64}$`)
)

// ErrPairingInvalid/ErrPairingExpired are exported for tests.
var (
	ErrPairingInvalid = errors.New("pairing secret invalid")
	ErrPairingExpired = errors.New("pairing secret expired")
)

// sanitizeFilename strips path components, control characters and other
// dangerous content from an upload filename.
func sanitizeFilename(name string) string {
	name = strings.ReplaceAll(name, "\\", "/")
	name = filepath.Base(name) // removes any directory component
	name = strings.Map(func(r rune) rune {
		switch {
		case r < 0x20 || r == 0x7f:
			return -1
		case r == '/' || r == '\\' || r == ':' || r == '*':
			return '_'
		case r == '"' || r == '<' || r == '>' || r == '|' || r == '?':
			return '_'
		default:
			return r
		}
	}, name)
	name = strings.TrimSpace(name)
	name = strings.TrimLeft(name, ".")
	if name == "" || name == "." || name == ".." {
		return ""
	}
	// Enforce a sane length limit (255 bytes is the filesystem max).
	for len(name) > 200 {
		name = strings.TrimSuffix(name[:200], ".")
	}
	return name
}

// sanitizeSubfolder accepts one optional directory name beneath DestDir.
// Nested and absolute paths are deliberately rejected.
func sanitizeSubfolder(name string) string {
	name = strings.TrimSpace(name)
	if name == "" || name == "." || name == ".." || len(name) > 80 {
		return ""
	}
	if filepath.IsAbs(name) || strings.ContainsAny(name, `/\\`) {
		return ""
	}
	for _, r := range name {
		if r < 0x20 || r == 0x7f || strings.ContainsRune(`:*?"<>|`, r) {
			return ""
		}
	}
	return name
}

// handleUpload receives one screenshot and stores it atomically.
func (s *Server) handleUpload(w http.ResponseWriter, r *http.Request) {
	deviceID := r.Header.Get("X-ShotSync-Device-ID")
	fileID := strings.TrimSpace(r.Header.Get("X-ShotSync-File-ID"))
	hashHeader := strings.ToLower(strings.TrimSpace(r.Header.Get("X-ShotSync-SHA256")))
	contentType := strings.ToLower(strings.TrimSpace(strings.SplitN(r.Header.Get("Content-Type"), ";", 2)[0]))
	filename := sanitizeFilename(strings.TrimSpace(r.Header.Get("X-ShotSync-Filename")))
	rawSubfolder := strings.TrimSpace(r.Header.Get("X-ShotSync-Destination-Subfolder"))
	subfolder := sanitizeSubfolder(rawSubfolder)

	logAttrs := []any{"device_id", deviceID, "file_id", fileID}
	if len(hashHeader) >= 8 {
		logAttrs = append(logAttrs, "sha256_prefix", hashHeader[:8])
	}

	if s.cfg.Paused {
		writeError(w, http.StatusServiceUnavailable, "paused", "receiver paused")
		return
	}
	if !s.limiter.take() {
		s.log.Warn("upload rate limited", logAttrs...)
		writeError(w, http.StatusTooManyRequests, "rate_limited", "too many uploads, retry later")
		return
	}
	if fileID == "" || !uuidRe.MatchString(fileID) {
		writeError(w, http.StatusBadRequest, "bad_request", "missing or invalid X-ShotSync-File-ID")
		return
	}
	if !sha256Re.MatchString(hashHeader) {
		writeError(w, http.StatusBadRequest, "bad_request", "missing or invalid X-ShotSync-SHA256")
		return
	}
	if !allowedMIME[contentType] {
		s.log.Warn("rejected content type", append(logAttrs, "content_type", contentType)...)
		writeError(w, http.StatusUnsupportedMediaType, "unsupported_media", "unsupported content type")
		return
	}
	if filename == "" {
		writeError(w, http.StatusBadRequest, "bad_request", "missing or invalid filename")
		return
	}
	if rawSubfolder != "" && subfolder == "" {
		writeError(w, http.StatusBadRequest, "bad_request", "invalid destination subfolder")
		return
	}
	// Ensure the extension matches the declared MIME type when present.
	if ext := strings.ToLower(filepath.Ext(filename)); ext != "" {
		if mimeForExt(ext) != "" && mimeForExt(ext) != contentType {
			writeError(w, http.StatusBadRequest, "bad_request", "filename extension does not match content type")
			return
		}
	}

	maxBytes := s.cfg.MaxSizeMB * 1024 * 1024
	if r.ContentLength > maxBytes {
		s.log.Warn("upload too large", append(logAttrs, "size", r.ContentLength)...)
		writeError(w, http.StatusRequestEntityTooLarge, "too_large", "file exceeds maximum size")
		return
	}
	if r.ContentLength < 0 {
		writeError(w, http.StatusLengthRequired, "length_required", "Content-Length required")
		return
	}

	// Deduplicate by content hash.
	if existing := s.state.FindBySHA256(hashHeader); existing != nil {
		if _, err := os.Stat(filepath.Join(s.cfg.DestDir, existing.Filename)); err == nil {
			s.log.Info("duplicate screenshot", append(logAttrs, "existing", existing.Filename)...)
			writeJSON(w, http.StatusOK, map[string]any{
				"status":   "already_exists",
				"file_id":  fileID,
				"sha256":   hashHeader,
				"filename": existing.Filename,
			})
			return
		}
		// Hash known but file vanished: fall through and re-store.
	}

	destDir := s.cfg.DestDir
	if subfolder != "" {
		destDir = filepath.Join(destDir, subfolder)
	}
	capturedAt := parseCapturedAt(r.Header.Get("X-ShotSync-Captured-At"))
	stored, respFilename, err := s.storeAtomic(r, fileID, hashHeader, filename, destDir, subfolder, capturedAt, logAttrs)
	if err != nil {
		var he *httpError
		if errors.As(err, &he) {
			s.log.Warn("upload rejected", append(logAttrs, "reason", he.msg)...)
			writeError(w, he.status, he.code, he.msg)
			return
		}
		s.log.Error("upload failed", append(logAttrs, "err", err.Error())...)
		writeError(w, http.StatusInternalServerError, "internal", "upload failed")
		return
	}

	s.log.Info("screenshot stored",
		append(logAttrs, "size", stored.size, "filename", respFilename, "duration_ms", stored.durationMs)...)
	if s.notify != nil {
		s.notify(deviceID, respFilename, contentType)
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"status":    "stored",
		"file_id":   fileID,
		"sha256":    hashHeader,
		"filename":  respFilename,
		"stored_at": time.Now().Format(time.RFC3339),
	})
}

type storedResult struct {
	size       int64
	durationMs int64
}

type httpError struct {
	status int
	code   string
	msg    string
}

func (e *httpError) Error() string { return e.msg }

// storeAtomic streams the request body into a temp file while hashing,
// validates the hash, then renames into place with collision handling.
func (s *Server) storeAtomic(r *http.Request, fileID, wantHash, filename, destDir, subfolder string, capturedAt time.Time, logAttrs []any) (storedResult, string, error) {
	start := time.Now()
	if err := os.MkdirAll(destDir, 0o755); err != nil {
		return storedResult{}, "", fmt.Errorf("create dest dir: %w", err)
	}

	tmp, err := os.CreateTemp(destDir, ".shotsync-tmp-*")
	if err != nil {
		return storedResult{}, "", fmt.Errorf("temp file: %w", err)
	}
	tmpName := tmp.Name()
	defer func() {
		_ = tmp.Close()
		if tmpName != "" {
			_ = os.Remove(tmpName)
		}
	}()

	hasher := sha256.New()
	limited := io.LimitReader(r.Body, s.cfg.MaxSizeMB*1024*1024+1)
	n, err := io.Copy(io.MultiWriter(tmp, hasher), limited)
	if err != nil {
		return storedResult{}, "", &httpError{http.StatusBadRequest, "interrupted", "body read interrupted"}
	}
	if n > s.cfg.MaxSizeMB*1024*1024 {
		return storedResult{}, "", &httpError{http.StatusRequestEntityTooLarge, "too_large", "file exceeds maximum size"}
	}
	gotHash := hex.EncodeToString(hasher.Sum(nil))
	if subtle.ConstantTimeCompare([]byte(gotHash), []byte(wantHash)) != 1 {
		s.log.Warn("hash mismatch", append(logAttrs, "expected_prefix", wantHash[:8], "got_prefix", gotHash[:8])...)
		return storedResult{}, "", &httpError{http.StatusBadRequest, "hash_mismatch", "content hash does not match header"}
	}
	if err := tmp.Sync(); err != nil {
		return storedResult{}, "", fmt.Errorf("fsync: %w", err)
	}
	if err := tmp.Close(); err != nil {
		return storedResult{}, "", fmt.Errorf("close: %w", err)
	}

	finalName := resolveCollision(destDir, filename, wantHash)
	finalPath := filepath.Join(destDir, finalName)
	if err := os.Rename(tmpName, finalPath); err != nil {
		tmpName = "" // rename failed; keep cleanup defer active but file may still exist
		return storedResult{}, "", fmt.Errorf("rename: %w", err)
	}
	tmpName = "" // renamed successfully; nothing to clean up

	if !capturedAt.IsZero() {
		_ = os.Chtimes(finalPath, capturedAt, capturedAt)
	}

	storedName := finalName
	if subfolder != "" {
		storedName = filepath.Join(subfolder, finalName)
	}
	if err := s.state.RecordTransfer(&store.Transfer{
		FileID:   fileID,
		SHA256:   wantHash,
		Filename: storedName,
		DeviceID: r.Header.Get("X-ShotSync-Device-ID"),
		Size:     n,
		StoredAt: time.Now(),
	}, 10000); err != nil {
		// The file is stored; a state-record failure must not lose the file,
		// but we report the error so the client retries (idempotent).
		s.log.Error("state record failed", "err", err)
	}

	return storedResult{size: n, durationMs: time.Since(start).Milliseconds()}, storedName, nil
}

// resolveCollision picks a filename that does not exist on disk, appending
// _2, _3, ... when a same-named file with different content is present.
func resolveCollision(dir, filename, wantHash string) string {
	candidate := filename
	for i := 2; ; i++ {
		full := filepath.Join(dir, candidate)
		if !fileExists(full) {
			return candidate
		}
		if fileSHA256(full) == wantHash {
			return candidate // identical content already there
		}
		ext := filepath.Ext(filename)
		base := strings.TrimSuffix(filename, ext)
		candidate = fmt.Sprintf("%s_%d%s", base, i, ext)
	}
}

func fileExists(path string) bool {
	fi, err := os.Stat(path)
	return err == nil && !fi.IsDir()
}

func fileSHA256(path string) string {
	f, err := os.Open(path)
	if err != nil {
		return ""
	}
	defer f.Close()
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return ""
	}
	return hex.EncodeToString(h.Sum(nil))
}

func parseCapturedAt(s string) time.Time {
	if s == "" {
		return time.Time{}
	}
	for _, layout := range []string{time.RFC3339, "2006-01-02T15:04:05.999999999Z07:00", "2006-01-02 15:04:05"} {
		if t, err := time.Parse(layout, s); err == nil {
			return t
		}
	}
	return time.Time{}
}

func mimeForExt(ext string) string {
	switch ext {
	case ".png":
		return "image/png"
	case ".jpg", ".jpeg":
		return "image/jpeg"
	case ".webp":
		return "image/webp"
	case ".heic":
		return "image/heic"
	case ".heif":
		return "image/heif"
	case ".gif":
		return "image/gif"
	case ".mp4":
		return "video/mp4"
	case ".webm":
		return "video/webm"
	case ".mov":
		return "video/quicktime"
	}
	return ""
}

// CleanTempFiles removes stale .shotsync-tmp-* files older than timeout.
func CleanTempFiles(dir string, olderThan time.Duration) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return
	}
	cutoff := time.Now().Add(-olderThan)
	for _, e := range entries {
		if !strings.HasPrefix(e.Name(), ".shotsync-tmp-") {
			continue
		}
		if info, err := e.Info(); err == nil && info.ModTime().Before(cutoff) {
			_ = os.Remove(filepath.Join(dir, e.Name()))
		}
	}
}
