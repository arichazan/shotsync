package identity

import (
	"crypto/rand"
	"crypto/tls"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net"
	"os"
)

// Persisted is the on-disk form of Identity. The private key is stored
// separately with restrictive permissions.
type Persisted struct {
	DeviceID        string `json:"device_id"`
	CertFingerprint string `json:"cert_fingerprint"`
	CertPEMB64      string `json:"cert_pem_b64"`
	KeyPEMB64       string `json:"key_pem_b64"`
}

func unmarshal(id *Identity, data []byte) error {
	var p Persisted
	if err := json.Unmarshal(data, &p); err != nil {
		return err
	}
	certPEM, err := base64.StdEncoding.DecodeString(p.CertPEMB64)
	if err != nil {
		return fmt.Errorf("decode cert: %w", err)
	}
	keyPEM, err := base64.StdEncoding.DecodeString(p.KeyPEMB64)
	if err != nil {
		return fmt.Errorf("decode key: %w", err)
	}
	tlsCert, err := tls.X509KeyPair(certPEM, keyPEM)
	if err != nil {
		return fmt.Errorf("parse key pair: %w", err)
	}
	id.DeviceID = p.DeviceID
	id.CertFingerprint = p.CertFingerprint
	id.CertPEM = certPEM
	id.KeyPEM = keyPEM
	id.TLSCert = tlsCert
	return nil
}

// Save writes the identity JSON with 0600 permissions.
func (id *Identity) Save(path string) error {
	p := Persisted{
		DeviceID:        id.DeviceID,
		CertFingerprint: id.CertFingerprint,
		CertPEMB64:      base64.StdEncoding.EncodeToString(id.CertPEM),
		KeyPEMB64:       base64.StdEncoding.EncodeToString(id.KeyPEM),
	}
	data, err := json.MarshalIndent(p, "", "  ")
	if err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

// NewPairingSecret returns a 256-bit random secret and its expiry time.
// The caller stores (secret, expiry) and exposes a short-lived QR code.
func NewPairingSecret() (string, error) {
	buf := make([]byte, 32)
	if _, err := rand.Read(buf); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(buf), nil
}

// NewDeviceToken returns a 256-bit random access token (raw bytes, hex-encoded).
func NewDeviceToken() (string, error) {
	buf := make([]byte, 32)
	if _, err := rand.Read(buf); err != nil {
		return "", err
	}
	return hex.EncodeToString(buf), nil
}

func newUUID() (string, error) {
	buf := make([]byte, 16)
	if _, err := rand.Read(buf); err != nil {
		return "", err
	}
	buf[6] = (buf[6] & 0x0f) | 0x40
	buf[8] = (buf[8] & 0x3f) | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", buf[0:4], buf[4:6], buf[6:8], buf[8:10], buf[10:16]), nil
}

func netIPs() []net.IP {
	var ips []net.IP
	ifaces, err := net.Interfaces()
	if err != nil {
		return ips
	}
	for _, iface := range ifaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, err := iface.Addrs()
		if err != nil {
			continue
		}
		for _, a := range addrs {
			if ipn, ok := a.(*net.IPNet); ok {
				ips = append(ips, ipn.IP)
			}
		}
	}
	return ips
}
