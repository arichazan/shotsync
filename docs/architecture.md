# Architecture

```
┌─────────────────────────── Android app ───────────────────────────┐
│                                                                    │
│  ContentObserver ──► MediaScanner ──► Reconciliation.diff ──┐       │
│  (MediaStore.Images)      │                                 ▼       │
│                           │                          Room queue     │
│  periodic worker ─────────┘                       (persistent)      │
│                                                          │         │
│   SyncEngine ──► ApiClient (OkHttp, pinned TLS) ──► HTTP POST       │
│      │            │                                    │           │
│   NSD discovery   └── RetryPolicy + WorkManager        │           │
│      │                                                 │           │
└──────┼─────────────────────────────────────────────────┼───────────┘
       │ mDNS _shotsync._tcp                             │ HTTPS/TLS1.3
       ▼                                                 ▼
┌──────────────────────────── Mac receiver ──────────────────────────┐
│                                                                     │
│  Go HTTP server (self-signed cert, Bearer auth, keychain tokens)   │
│      │                                                              │
│      ├─► POST /api/v1/screenshots ──► stream + SHA-256 verify       │
│      │                                    │                         │
│      │                        atomic write (tmp + rename)           │
│      │                                    │                         │
│      │                        dedupe by hash (state.json)           │
│      │                                    │                         │
│      └─► user-selected destination folder └─► macOS notification   │
│                                                                     │
│  Bonjour advertiser · launchd agent · menu-bar app (status client)  │
└─────────────────────────────────────────────────────────────────────┘
```

## Components

### Android (`android/`)

| Component | Purpose |
|---|---|
| `media/ScreenshotObserver` | Debounced `ContentObserver` on the shared MediaStore images URI |
| `media/MediaScanner` | Scans recent screenshots, waits for `IS_PENDING=0`, streams SHA-256 |
| `media/ScreenshotClassifier` | Pure path/filename screenshot heuristics (unit-tested) |
| `data/db` | Room queue: `queue_items` with the PRD's fields + statuses |
| `data/Prefs` | DataStore settings; Keystore-encrypted token |
| `sync/SyncEngine` | Endpoint selection, queue processing, retries, recovery |
| `sync/ApiClient` | OkHttp client with pinning; streamed request bodies |
| `sync/Discovery` | NsdManager `_shotsync._tcp` discovery |
| `sync/MonitorService` | `specialUse` foreground service (persistent notification) |
| `sync/MonitorStartReceiver` | Restarts monitoring on boot / app update when enabled |
| `sync/Workers` | WorkManager retry + periodic reconciliation |

### Mac (`mac/`)

| Component | Purpose |
|---|---|
| `internal/server` | TLS server, auth, pairing, uploads, rate limit, admin endpoints |
| `internal/store` | JSON state: devices, transfer history (dedupe) |
| `internal/keychain` | Token storage: Security framework via cgo on macOS (`SecItem*`), 0600 file fallback elsewhere |
| `internal/identity` | Device ID + self-signed CA/leaf generation |
| `internal/bonjour` | Minimal mDNS responder (x/net/dns/dnsmessage) |
| `internal/install` | launchd agent management |
| `internal/qr` / `internal/notify` | QR rendering / osascript notifications |
| `cmd/shotsync` | CLI + menu bar (fyne.io/systray) |
| `cmd/perfbench` | Latency benchmark |

## Reliability invariants

1. Nothing is marked complete without a receiver acknowledgement.
2. Queue rows survive transient failures; backoff retries persist across
   reboots via WorkManager.
3. Duplicate observer events are filtered by the unique `mediaStoreId`
   index; identical content with different names is deduplicated by the
   receiver's content-hash check.
4. Uploads are streamed end-to-end (no whole-image buffering).
5. The Mac writes atomically and only acknowledges after the hash matches.
