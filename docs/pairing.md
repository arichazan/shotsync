# Pairing

Pairing exchanges a one-time secret out-of-band (QR code) for a permanent,
per-device access token, and pins the Mac's certificate fingerprint.

## Steps

1. Make sure the receiver is running (`shotsync status`).
2. Open the pairing QR on the Mac:
   - Menu bar: ShotSync → "Show Pairing QR Code…" (opens the QR image), or
   - Terminal: `shotsync gen-qr`.
3. On Android: open ShotSync → first-run setup, or Settings → "Pair with
   your Mac". Scan the QR (or paste the pairing JSON).
4. Confirm pairing on the Android status screen: the paired Mac name should
   appear.

## What the QR contains

```json
{
  "version": 1,
  "device_id": "uuid",
  "device_name": "ShotSync Receiver",
  "local_host": "shotsync-a1b2c3d4e5f6.local",
  "port": 47831,
  "certificate_fingerprint": "sha256-…",
  "pairing_secret": "…one-time…"
}
```

## Security properties

- The pairing secret is 256 bits of randomness, valid for **5 minutes**,
  single-use: a successful pair invalidates it immediately.
- The Android app pins the `certificate_fingerprint` from the QR before the
  first HTTPS request, so the one-time secret is never sent over a
  connection whose certificate was not already authenticated by the pin.
- The exchanged access token is another 256-bit random value, stored in the
  macOS Keychain (receiver) and encrypted with an Android Keystore AES-GCM
  key (device). Tokens are never logged.
- Every upload is authenticated with `Authorization: Bearer <token>` plus
  `X-ShotSync-Device-ID`; comparisons are constant-time.

## Unpairing

- Android: Settings → "Unpair Mac" (also revokes the token on the Mac when
  reachable).
- Mac: `shotsync unpair <device-id>` or `shotsync devices` to list devices.
  Revoking the token makes the phone's uploads fail with 401; the app marks
  them FAILED and shows a clear error.

## Re-pairing after Mac reinstall

A fresh receiver install generates a new device ID, certificate and
fingerprint. Pair again via QR as above. Old Android devices still show up
in the new receiver's state only if the old state directory survived —
otherwise they are unknown devices and their tokens are rejected.
