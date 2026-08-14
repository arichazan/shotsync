# ShotSync

Instant, direct screenshot and screen-recording sync from an Android phone
to a Mac — with no cloud in between. Screenshots land in the folder selected
during receiver installation within ~2 seconds on the same Wi-Fi, and
media is queued while the Mac is offline or on a Tailscale network.

```
Android screenshot/recording → MediaStore observers → persistent queue → authenticated HTTPS → Mac folder
```

## Repository layout

```
android/     Kotlin Android app (Jetpack Compose, minSdk 29)
mac/         Go receiver: HTTP server, CLI, launchd agent, menu bar
protocol/    Versioned protocol specification (v1)
scripts/     Build, integration and performance test scripts
docs/        Build, install, pairing, troubleshooting, platform notes
```

## Quick start

### 1. Mac receiver

```bash
# Build (requires Go ≥ 1.26)
./scripts/build_mac.sh

# Install as a login agent and start
./build/shotsync install --destination "$HOME/Media/ShotSync"
./build/shotsync status          # verify
```

The receiver listens on port 47831 (HTTPS, self-signed cert), advertises
itself via Bonjour (`_shotsync._tcp`), and shows a menu bar status app with
`./build/shotsync menu`.

### 2. Android app

```bash
# Requires Android SDK + JDK 17 (see docs/build-android.md)
./scripts/build_android.sh
# install: adb install android/app/build/outputs/apk/debug/app-debug.apk
```

### 3. Pair

1. On the Mac: click the ShotSync menu bar icon → "Show Pairing QR Code…"
   (or run `./build/shotsync gen-qr`).
2. On Android: open ShotSync, follow the setup, scan the QR code.
3. Take a screenshot — it appears in the destination selected during installation.

## How it works

- **Detection**: `ContentObserver`s on the shared MediaStore image *and*
  video collections spot new screenshots and screen recordings (path +
  filename heuristics per manufacturer), backed by periodic reconciliation
  scans so nothing is missed after process death or reboots.
- **Queue**: Room-backed persistent queue (DISCOVERED → READY → UPLOADING →
  COMPLETED / RETRY_WAIT / FAILED) with exponential backoff and
  single-upload-at-a-time, oldest first.
- **Transfer**: HTTPS only (TLS 1.3) with certificate pinning, per-device
  bearer tokens (256-bit, revocable, Keychain/Keystore-stored), streamed
  SHA-256-verified uploads with atomic writes on the Mac.
- **Recovery**: WorkManager retries, network-change callbacks, Wi-Fi-only
  mode, foreground monitoring service, reconciliation on start/reboot.
- **Connectivity**: mDNS discovery preferred, optional Tailscale fallback,
  offline queueing when neither is reachable.

## Security model

See `docs/security.md` and `protocol/protocol.md`. In short: no plaintext
HTTP ever, no global TLS bypass, no secrets logged, one-time pairing codes
expiring in 5 minutes, tokens revocable from both sides, and the receiver
rejects unauthenticated, oversized, or non-media uploads.

## Testing

```bash
./scripts/run_all_tests.sh        # Go + Android unit tests + protocol integration
./scripts/perf_test.sh            # latency benchmark (median/p95/max)
```

## Documentation

- [Install the Mac receiver](docs/install-mac.md)
- [Build the Android app](docs/build-android.md)
- [Pairing](docs/pairing.md)
- [Troubleshooting](docs/troubleshooting.md)
- [Android platform notes](docs/android-platform-notes.md)
- [Security](docs/security.md)
- [Architecture](docs/architecture.md)
- [Performance](docs/performance.md)
- [Protocol specification](protocol/protocol.md)

## License

MIT — see [LICENSE](LICENSE).
