//go:build darwin && cgo

package keychain

/*
#cgo CFLAGS: -Wno-deprecated-declarations
#cgo LDFLAGS: -framework Security -framework CoreFoundation
#include <stdlib.h>
#include <string.h>
#include <CoreFoundation/CoreFoundation.h>
#include <Security/Security.h>

// ss_query builds a generic-password query for service/account. When noUI is
// set, Keychain access prompts are suppressed (the call fails with
// errSecInteractionNotAllowed instead of blocking on a dialog).
static CFMutableDictionaryRef ss_query(const char *service, const char *account, int noUI) {
	CFStringRef s = CFStringCreateWithCString(kCFAllocatorDefault, service, kCFStringEncodingUTF8);
	CFStringRef a = CFStringCreateWithCString(kCFAllocatorDefault, account, kCFStringEncodingUTF8);
	if (s == NULL || a == NULL) {
		if (s) CFRelease(s);
		if (a) CFRelease(a);
		return NULL;
	}
	CFMutableDictionaryRef q = CFDictionaryCreateMutable(kCFAllocatorDefault, 0,
		&kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
	CFDictionarySetValue(q, kSecClass, kSecClassGenericPassword);
	CFDictionarySetValue(q, kSecAttrService, s);
	CFDictionarySetValue(q, kSecAttrAccount, a);
	if (noUI) {
		CFDictionarySetValue(q, kSecUseAuthenticationUI, kSecUseAuthenticationUIFail);
	}
	CFRelease(s);
	CFRelease(a);
	return q;
}

static OSStatus ss_update(const char *service, const char *account, const void *data, long len) {
	CFMutableDictionaryRef q = ss_query(service, account, 1);
	if (q == NULL) return errSecParam;
	CFDataRef d = CFDataCreate(kCFAllocatorDefault, (const UInt8 *)data, (CFIndex)len);
	CFMutableDictionaryRef attrs = CFDictionaryCreateMutable(kCFAllocatorDefault, 0,
		&kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
	CFDictionarySetValue(attrs, kSecValueData, d);
	OSStatus st = SecItemUpdate(q, attrs);
	CFRelease(attrs);
	CFRelease(d);
	CFRelease(q);
	return st;
}

static OSStatus ss_add(const char *service, const char *account, const void *data, long len) {
	CFMutableDictionaryRef q = ss_query(service, account, 0);
	if (q == NULL) return errSecParam;
	CFDataRef d = CFDataCreate(kCFAllocatorDefault, (const UInt8 *)data, (CFIndex)len);
	CFDictionarySetValue(q, kSecValueData, d);
	CFDictionarySetValue(q, kSecAttrLabel, CFSTR("ShotSync device token"));
	OSStatus st = SecItemAdd(q, NULL);
	CFRelease(d);
	CFRelease(q);
	return st;
}

static OSStatus ss_get(const char *service, const char *account, void **out, long *outLen) {
	*out = NULL;
	*outLen = 0;
	CFMutableDictionaryRef q = ss_query(service, account, 1);
	if (q == NULL) return errSecParam;
	CFDictionarySetValue(q, kSecReturnData, kCFBooleanTrue);
	CFDictionarySetValue(q, kSecMatchLimit, kSecMatchLimitOne);
	CFTypeRef res = NULL;
	OSStatus st = SecItemCopyMatching(q, &res);
	CFRelease(q);
	if (st != errSecSuccess) return st;
	if (res == NULL || CFGetTypeID(res) != CFDataGetTypeID()) {
		if (res) CFRelease(res);
		return errSecDecode;
	}
	CFIndex n = CFDataGetLength((CFDataRef)res);
	void *buf = malloc(n > 0 ? (size_t)n : 1);
	if (buf == NULL) {
		CFRelease(res);
		return errSecAllocate;
	}
	if (n > 0) memcpy(buf, CFDataGetBytePtr((CFDataRef)res), (size_t)n);
	CFRelease(res);
	*out = buf;
	*outLen = (long)n;
	return errSecSuccess;
}

static OSStatus ss_delete(const char *service, const char *account) {
	CFMutableDictionaryRef q = ss_query(service, account, 0);
	if (q == NULL) return errSecParam;
	OSStatus st = SecItemDelete(q);
	CFRelease(q);
	return st;
}
*/
import "C"

import (
	"fmt"
	"os/exec"
	"strings"
	"unsafe"
)

const nativeAvailable = true

// statusError wraps a Security framework OSStatus.
type statusError int32

func (e statusError) Error() string { return fmt.Sprintf("keychain: OSStatus %d", int32(e)) }

func cstrs(svc, account string) (*C.char, *C.char, func()) {
	s, a := C.CString(svc), C.CString(account)
	return s, a, func() { C.free(unsafe.Pointer(s)); C.free(unsafe.Pointer(a)) }
}

// needsLegacy reports whether a status means the item exists but this
// binary is not on its access list: either it was created by the old
// `security` CLI path (trusted app /usr/bin/security) or by an earlier,
// differently-signed build of this binary.
func needsLegacy(st C.OSStatus) bool {
	return st == C.errSecInteractionNotAllowed || st == C.errSecAuthFailed
}

func nativeSet(svc, account, token string) error {
	if token == "" {
		return fmt.Errorf("keychain: empty token")
	}
	s, a, free := cstrs(svc, account)
	defer free()
	data := C.CBytes([]byte(token))
	defer C.free(data)
	n := C.long(len(token))

	st := C.ss_update(s, a, data, n)
	if st == C.errSecSuccess {
		return nil
	}
	if st != C.errSecItemNotFound {
		// Existing item we cannot modify (foreign ACL) or another error:
		// remove it and recreate it owned by this binary.
		nativeDelete(svc, account)
	}
	st = C.ss_add(s, a, data, n)
	if st != C.errSecSuccess {
		return statusError(st)
	}
	return nil
}

func nativeGet(svc, account string) (string, bool) {
	s, a, free := cstrs(svc, account)
	defer free()
	var out unsafe.Pointer
	var n C.long
	st := C.ss_get(s, a, &out, &n)
	if st == C.errSecSuccess {
		b := C.GoBytes(out, C.int(n))
		C.free(out)
		return string(b), true
	}
	if !needsLegacy(st) {
		return "", false
	}
	// Item exists but is not readable without a prompt. Read it with the
	// `security` CLI (the secret only travels over its stdout pipe, never
	// argv), then re-store it natively so later reads need no subprocess.
	token, ok := legacyGet(svc, account)
	if !ok {
		return "", false
	}
	_ = nativeSet(svc, account, token)
	return token, true
}

func nativeDelete(svc, account string) {
	s, a, free := cstrs(svc, account)
	defer free()
	st := C.ss_delete(s, a)
	if st != C.errSecSuccess && st != C.errSecItemNotFound {
		// Deleting by service/account carries no secret on the command line.
		_ = exec.Command("/usr/bin/security", "delete-generic-password", "-s", svc, "-a", account).Run()
	}
}

func legacyGet(svc, account string) (string, bool) {
	out, err := exec.Command("/usr/bin/security", "find-generic-password", "-s", svc, "-a", account, "-w").Output()
	if err != nil {
		return "", false
	}
	return strings.TrimRight(string(out), "\n"), true
}
