package identity

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/pem"
	"fmt"
	"math/big"
	"net"
	"os"
	"path/filepath"
	"strings"
	"time"
)

const (
	certValidity = 10 * 365 * 24 * time.Hour
)

// Identity holds the receiver's persistent identity: device ID plus a
// self-signed CA and a leaf certificate. Android pins the leaf certificate
// fingerprint exchanged during pairing.
type Identity struct {
	DeviceID        string
	CertPEM         []byte
	KeyPEM          []byte
	CertFingerprint string // "sha256-<base64>" of the DER certificate
	TLSCert         tls.Certificate
}

// LoadOrCreate returns the stored identity or generates a new one under dir.
func LoadOrCreate(dir string) (*Identity, error) {
	idPath := filepath.Join(dir, "identity.json")
	data, err := os.ReadFile(idPath)
	if err == nil {
		var id Identity
		if err := unmarshal(&id, data); err != nil {
			return nil, fmt.Errorf("corrupt identity file %s: %w", idPath, err)
		}
		return &id, nil
	}
	if !os.IsNotExist(err) {
		return nil, err
	}
	id, err := generate(dir)
	if err != nil {
		return nil, err
	}
	return id, nil
}

// FingerprintOf computes the "sha256-<base64>" fingerprint of a DER certificate.
func FingerprintOf(der []byte) string {
	sum := sha256.Sum256(der)
	return "sha256-" + base64.StdEncoding.EncodeToString(sum[:])
}

func generate(dir string) (*Identity, error) {
	deviceID, err := newUUID()
	if err != nil {
		return nil, err
	}

	// CA
	caKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, err
	}
	caTmpl := &x509.Certificate{
		SerialNumber:          big.NewInt(1),
		Subject:               pkix.Name{CommonName: "ShotSync " + deviceID},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().Add(certValidity),
		IsCA:                  true,
		KeyUsage:              x509.KeyUsageCertSign | x509.KeyUsageDigitalSignature,
		BasicConstraintsValid: true,
	}
	caDER, err := x509.CreateCertificate(rand.Reader, caTmpl, caTmpl, &caKey.PublicKey, caKey)
	if err != nil {
		return nil, err
	}

	// Leaf certificate, signed by the CA, uses a generated alias rather than
	// exposing the computer's configured hostname.
	leafKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, err
	}
	host := NetworkHost(deviceID)
	ips := append(netIPs(), net.ParseIP("127.0.0.1"), net.ParseIP("::1"))
	leafTmpl := &x509.Certificate{
		SerialNumber: big.NewInt(2),
		Subject:      pkix.Name{CommonName: host},
		DNSNames:     []string{"localhost", host + ".local", host},
		IPAddresses:  ips,
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(certValidity),
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	leafDER, err := x509.CreateCertificate(rand.Reader, leafTmpl, caTmpl, &leafKey.PublicKey, caKey)
	if err != nil {
		return nil, err
	}

	// PEM-encode leaf first (tls.X509KeyPair validates Certificate[0]
	// against the private key), then the CA.
	certPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: leafDER})
	certPEM = append(certPEM, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: caDER})...)
	keyDER, err := x509.MarshalECPrivateKey(leafKey)
	if err != nil {
		return nil, err
	}
	keyPEM := pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER})

	tlsCert, err := tls.X509KeyPair(certPEM, keyPEM)
	if err != nil {
		return nil, err
	}

	id := &Identity{
		DeviceID:        deviceID,
		CertPEM:         certPEM,
		KeyPEM:          keyPEM,
		CertFingerprint: FingerprintOf(leafDER),
		TLSCert:         tlsCert,
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, err
	}
	if err := id.Save(filepath.Join(dir, "identity.json")); err != nil {
		return nil, err
	}
	return id, nil
}

// NetworkHost returns a stable, privacy-safe local hostname derived from the
// random receiver ID rather than the user's computer name.
func NetworkHost(deviceID string) string {
	compact := strings.ReplaceAll(strings.ToLower(deviceID), "-", "")
	if len(compact) > 12 {
		compact = compact[:12]
	}
	if compact == "" {
		compact = "receiver"
	}
	return "shotsync-" + compact
}
