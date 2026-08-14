# Security model

Screenshots can contain passwords, conversations and financial data. The
transfer path is designed accordingly.

## Transport

- HTTPS only; TLS 1.3 minimum on both ends.
- The receiver generates a per-install self-signed ECDSA P-256 certificate.
- Android pins the certificate's SHA-256 fingerprint (exchanged in the
  pairing QR) via a custom `X509TrustManager` with constant-time
  comparison. TLS validation is **never** disabled globally; it is narrowed
  to exactly the pinned certificate. A hostname allowlist adds
  defense-in-depth for the endpoints the app is configured to use
  (local host, Tailscale host, LAN/Tailscale IP literals).

## Authentication and pairing

- Pairing secret: 256-bit random, single-use, expires in 5 minutes.
- Access token: 256-bit random per device, compared in constant time,
  revocable from both the Mac (`shotsync unpair`) and the phone (unpair
  also calls the revoke endpoint when reachable).
- Token storage: macOS Keychain on the Mac; Android Keystore AES-256-GCM
  encryption on the phone.
- mDNS TXT records contain no secrets. The pairing QR intentionally contains
  a short-lived, single-use secret in addition to the receiver ID, generic
  display name, generated network alias, port, and certificate fingerprint.
- Hardware model names and the Mac hostname are not advertised by default.
  Users may choose their own receiver and Android device display names.

## Receiver hardening

- Unauthenticated requests are rejected.
- Uploads must declare `Content-Length`; a configurable maximum (default
  500 MB) is enforced both on the declared length and the actual streamed
  byte count.
- Only image MIME types are accepted, and the filename extension must match
  the declared type.
- Filenames are sanitized; path traversal is impossible; files are written
  atomically (temp file + rename) and never executed or opened.
- Uploads are rate-limited (token bucket, configurable).
- Admin endpoints (`/api/v1/status`, `/api/v1/admin/*`) are loopback-only.
- The service is intended for LAN/Tailscale only; do not expose it to the
  public internet.

## Logging discipline

Logs contain file IDs, device IDs, sizes, hash **prefixes**, timestamps,
connection method, HTTP statuses, and final filenames. They never contain
image contents, tokens, pairing secrets, private keys, or complete request
headers.

## Known trade-offs

- Certificate pinning means the Android app rejects a legitimately rotated
  Mac identity; the fix is re-pairing via QR (intended behavior).
- The health endpoint is unauthenticated but exposes only status, protocol
  version and the Mac's display name.
- Device tokens are stored server-side in the macOS Keychain; a local
  attacker with full user access to the Mac is outside the threat model.
