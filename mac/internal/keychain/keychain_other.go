//go:build !darwin || !cgo

package keychain

import "errors"

// No native Keychain off macOS (or without cgo): the 0600 file is used.
const nativeAvailable = false

func nativeSet(_, _, _ string) error {
	return errors.New("keychain: native backend unavailable")
}

func nativeGet(_, _ string) (string, bool) { return "", false }

func nativeDelete(_, _ string) {}
