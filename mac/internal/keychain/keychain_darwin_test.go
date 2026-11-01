//go:build darwin && cgo

package keychain

import (
	"errors"
	"fmt"
	"os"
	"testing"
)

// Exercises the Security-framework backend against the login Keychain under
// a throwaway service name, so real receiver items are never touched.
func TestNativeRoundTrip(t *testing.T) {
	old := service
	service = fmt.Sprintf("com.shotsync.receiver.test.%d", os.Getpid())
	t.Cleanup(func() { nativeDelete(service, "dev-native"); service = old; resetCache() })

	if !Available() {
		t.Fatal("native backend should be available on darwin+cgo")
	}
	if err := Set("dev-native", "tok-a"); err != nil {
		var se statusError
		// -25307 errSecNoDefaultKeychain, -25308 errSecInteractionNotAllowed
		if errors.As(err, &se) && (se == -25307 || se == -25308) {
			t.Skipf("no usable login keychain in this environment: %v", err)
		}
		t.Fatal(err)
	}
	resetCache()
	if got, ok := Get("dev-native"); !ok || got != "tok-a" {
		t.Fatalf("Get = %q,%v", got, ok)
	}
	if err := Set("dev-native", "tok-b"); err != nil {
		t.Fatal(err)
	}
	resetCache()
	if got, ok := Get("dev-native"); !ok || got != "tok-b" {
		t.Fatalf("Get after update = %q,%v", got, ok)
	}
	Delete("dev-native")
	resetCache()
	if _, ok := Get("dev-native"); ok {
		t.Fatal("token still present after Delete")
	}
}
