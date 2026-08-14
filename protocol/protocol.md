# ShotSync Protocol — Version 1

Direct, authenticated, encrypted transfer of screenshots from an Android
device to a Mac. No cloud services are involved at any point.

## Transport

- HTTPS only, TLS 1.3 minimum.
- The receiver uses a self-signed certificate (ECDSA P-256) generated once
  per Mac install.
- The Android client pins the certificate SHA-256 fingerprint exchanged
  during pairing. TLS validation is never disabled globally; it is narrowed
  to the pinned certificate.

## Discovery

The receiver advertises an mDNS/Bonjour service:

| Field  | Value                    |
|--------|--------------------------|
| Type   | `_shotsync._tcp`         |
| Port   | receiver port (default `47831`) |
| TXT    | `protocol_version=1`     |
| TXT    | `device_id=<uuid>`       |
| TXT    | `device_name=<name>`     |
| TXT    | `port=<port>`            |

No secrets are advertised. The Android client prefers a discovered local
endpoint over a configured Tailscale endpoint, then falls back to offline
queueing.

## Pairing

1. The user requests a pairing QR code from the receiver (menu bar or CLI).
   The receiver generates a one-time secret (256-bit random) valid for
   5 minutes and encodes it in a QR:

   ```json
   {
     "version": 1,
     "device_id": "uuid",
     "device_name": "ShotSync Receiver",
     "local_host": "shotsync-a1b2c3d4e5f6.local",
     "port": 47831,
     "certificate_fingerprint": "sha256-<base64>",
     "pairing_secret": "<one-time-secret>"
   }
   ```

2. The Android app scans the QR, pins the fingerprint, and calls the pair
   endpoint over HTTPS.

3. On success the receiver issues a permanent device-specific access token
   (256-bit random) and invalidates the one-time secret.

4. The token is stored in the macOS Keychain (receiver side) and encrypted
   with an Android Keystore AES-GCM key (device side).

### Pair endpoint

```http
POST /api/v1/pair
Content-Type: application/json

{
  "pairing_secret": "<secret from QR>",
  "device_id": "<android device uuid>",
  "device_name": "Android device"
}
```

Responses:

- `200` — `{"status":"paired","device_token":"<hex token>"}`
- `401` — `{"status":"error","error":"pairing_failed",...}` (invalid or expired secret)
- `400` — malformed request

## Authentication

Every authenticated request carries:

```http
Authorization: Bearer <device-token>
X-ShotSync-Device-ID: <device-id>
```

- Tokens are compared in constant time.
- Tokens are revocable: `DELETE /api/v1/device/<device-id>` (authenticated)
  removes the device and its token. Unpairing is also possible from the Mac
  side (`shotsync unpair <id>` or the menu bar).
- Logs may contain device IDs but never tokens.

## Upload

Screenshots and screen recordings share the same upload endpoint; the
content type distinguishes them.

```http
POST /api/v1/screenshots
Authorization: Bearer <token>
X-ShotSync-Device-ID: <device-id>
X-ShotSync-File-ID: <uuid>
X-ShotSync-SHA256: <hex sha256 of the raw body>
X-ShotSync-Filename: <sanitized original filename>
X-ShotSync-Destination-Subfolder: <optional single directory name>
X-ShotSync-Captured-At: <ISO-8601, optional>
Content-Type: image/png | image/jpeg | image/webp | image/heic | image/heif | image/gif
            | video/mp4 | video/webm | video/quicktime
Content-Length: <bytes>

<raw media bytes>
```

The optional destination subfolder is created beneath the Mac destination.
Absolute paths, nested paths, traversal components, and control characters
are rejected.

### Success

```json
{
  "status": "stored",
  "file_id": "uuid",
  "sha256": "hex-hash",
  "filename": "Screenshot_2026-08-14_09-45-21.png",
  "stored_at": "2026-08-14T09:45:22+02:00"
}
```

### Duplicate (same content hash already stored)

```json
{
  "status": "already_exists",
  "file_id": "uuid",
  "sha256": "hex-hash",
  "filename": "Screenshot_2026-08-14_09-45-21.png"
}
```

Both responses count as successful delivery. The client must not mark a
file complete without one of these acknowledgements.

### Errors

| HTTP | `error` code        | Meaning                              | Client action    |
|------|---------------------|--------------------------------------|------------------|
| 400  | `bad_request`       | missing/invalid headers              | permanent fail   |
| 400  | `hash_mismatch`     | body hash ≠ header hash              | permanent fail   |
| 400  | `interrupted`       | body truncated                      | retry (backoff)  |
| 401  | `unauthorized`      | missing/revoked token               | permanent fail   |
| 411  | `length_required`   | Content-Length missing              | permanent fail   |
| 413  | `too_large`         | exceeds `max_size_mb` (default 30)  | permanent fail   |
| 415  | `unsupported_media` | MIME not in allowlist               | permanent fail   |
| 429  | `rate_limited`      | client throttled                    | retry (backoff)  |
| 503  | `paused`            | receiver paused via menu bar        | retry (backoff)  |

"Permanent" failures are kept in the queue as `FAILED` and can be retried
manually. Transient failures stay in the queue as `RETRY_WAIT` with
exponential backoff (15 s base, doubled, 10 min cap, jitter).

## Health

```http
GET /api/v1/health
```

```json
{"status":"ok|paused","protocol_version":1,"device_name":"ShotSync Receiver"}
```

Unauthenticated by design and deliberately minimal: no filesystem paths, no
usernames, no secrets.

## Receiver-side storage rules

1. The body is streamed into a temp file in the destination directory while
   the SHA-256 is computed in parallel. Never buffered whole in memory.
2. Hash mismatch ⇒ temp file deleted, `400 hash_mismatch`.
3. On match: fsync, close, rename into place.
4. Filename collisions get `_2`, `_3`, … suffixes unless the existing file
   has the same content hash (then `already_exists` is returned instead).
5. The capture timestamp is applied as the file modification time.
6. Stale `.shotsync-tmp-*` files from crashes are cleaned at startup.
7. Filenames are sanitized: path separators removed, control characters
   stripped, length capped; path traversal is impossible.

## Receiver protections

- Unauthenticated uploads are rejected.
- Maximum file size (configurable, default 500 MB — covers long screen
  recordings) enforced on both `Content-Length` and the actual streamed
  byte count.
- Only image and video MIME types are accepted, and the filename extension must match
  the declared MIME type when present.
- Uploads are rate-limited (token bucket, 30/min sustained, burst 15).
- Uploaded files are never executed or automatically opened.
- The receiver binds to the configured interface and is intended for LAN /
  Tailscale use only — do not port-forward it to the public internet.
- Admin endpoints (`/api/v1/status`, `/api/v1/admin/*`) are loopback-only.

## Versioning

All breaking changes must bump `protocol_version` (TXT record, health
endpoint, QR payload `version`) and update this document. Version 1 is
frozen as specified here.
