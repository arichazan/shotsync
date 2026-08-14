package identity

import (
	"crypto/x509"
	"strings"
	"testing"
)

func TestNetworkHostIsStableGeneratedAlias(t *testing.T) {
	id := "A1B2C3D4-E5F6-4789-ABCD-0123456789AB"
	got := NetworkHost(id)
	if got != "shotsync-a1b2c3d4e5f6" {
		t.Fatalf("NetworkHost() = %q", got)
	}
	if strings.Contains(got, " ") || strings.Contains(got, ".local") {
		t.Fatalf("network alias is not a bare mDNS host label: %q", got)
	}
}

func TestGeneratedCertificateUsesNetworkAlias(t *testing.T) {
	id, err := generate(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	leaf, err := x509.ParseCertificate(id.TLSCert.Certificate[0])
	if err != nil {
		t.Fatal(err)
	}
	host := NetworkHost(id.DeviceID)
	if leaf.Subject.CommonName != host {
		t.Fatalf("certificate common name = %q, want %q", leaf.Subject.CommonName, host)
	}
	for _, name := range leaf.DNSNames {
		if name != "localhost" && name != host && name != host+".local" {
			t.Fatalf("certificate exposed unexpected DNS name %q", name)
		}
	}
}
