# Troubleshooting

## Mac

### `shotsync status` says "not installed"

Run `./build/shotsync install --destination "/absolute/path"` first. Reboot
or re-login is not required.

### Screenshots don't arrive

1. `shotsync logs` — look for `device paired`, `receiver listening`,
   `mdns advertised`, `request … /api/v1/screenshots status=200`.
2. Check the firewall: System Settings → Network → Firewall must allow
   incoming connections for the `shotsync` binary (or disable "Block all
   incoming connections").
3. Run `shotsync status` and verify the configured destination is writable.
4. If paused via the menu bar, uploads return `paused` — un-pause.

### `rate_limited` errors in the logs

Normal protection when many files arrive at once; the phone retries with
backoff. Raise `rate_limit_per_min` in config.json if desired.

### Pairing QR code is stale

Pairing secrets expire after 5 minutes. Show a fresh QR.

### Port 47831 in use

Change `port` in config.json and restart (`shotsync start`). Re-pair if the
QR was already scanned with the old port.

## Android

### "Receiver offline — queued" even though the Mac is on

- Both devices must be on the same Wi-Fi network (mDNS does not cross
  subnets/VLANs). Some routers block multicast: set the Tailscale hostname
  (or the Mac's local IP) in Settings → Tailscale fallback.
- Make sure the Mac firewall allows the connection (above).
- Tap the refresh button on the main screen to re-probe.

### Screenshots are detected but never upload

- Check Settings → "Upload on Wi-Fi only" — disable if on mobile data.
- Check the diagnostics view: hash/`missing hash`, auth errors (401 = token
  revoked: re-pair), `too_large` (raise `max_size_mb` on the Mac).

### Monitoring stops when the screen is off

- Confirm battery optimization exclusion in setup (or Android Settings →
  Apps → ShotSync → Battery → Unrestricted).
- Manufacturer extras:
  - **Xiaomi/MIUI**: enable Autostart for ShotSync and set battery saver to
    "No restrictions".
  - **Huawei/EMUI**: App launch → manage manually → all three toggles on.
  - **Samsung/One UI**: Settings → Apps → ShotSync → Battery → Unrestricted;
    disable "Put unused apps to sleep".
  - **Oppo/ColorOS, OnePlus**: disable battery optimization and enable
    "allow auto-launch".
- Even if the process is killed, the reconciliation worker recovers missed
  screenshots within 15 minutes (or immediately after reopening the app).

### Duplicates on the Mac

The receiver deduplicates by content hash. If you still see `_2` files with
identical names, the content differed (e.g. re-encoded) — that is by design.

### Notification missing on Android 13+

Enable notifications in system settings for ShotSync (or re-run setup).

## Recovery model (what guarantees nothing is lost)

- Queue rows are never deleted on transient failure; only after Mac
  acknowledgement (`stored`/`already_exists`).
- Backoff retries persist via WorkManager across reboots.
- Reconciliation scans catch anything the observer missed.
- The Mac stores atomically and verifies the SHA-256 before acknowledging.
