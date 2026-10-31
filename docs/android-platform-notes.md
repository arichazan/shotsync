# Android platform notes

ShotSync targets the current Android behavior and documents every
restriction it works around.

## Foreground service type

Monitoring uses `android:foregroundServiceType="specialUse"` with
`PROPERTY_SPECIAL_USE_FGS_SUBTYPE` explaining the use. A continuously
running `dataSync` service would hit cumulative background time limits
(6h per 24h on recent Android) and stop the observer — so it is deliberately
**not** used. `specialUse` describes the continuous, user-enabled MediaStore
monitoring performed by this app.

Android 14+ normally does not allow starting a foreground service from the
background. ShotSync restarts monitoring in three foreground-allowed ways:

- When the user enables monitoring or opens the app (visible activity).
- After a reboot: `BOOT_COMPLETED` and app updates (`MY_PACKAGE_REPLACED`)
  temporarily allowlist the app for foreground-service start for ~10s, which
  `MonitorStartReceiver` uses to restart the service when monitoring is
  enabled.
- WorkManager persists and re-enqueues the periodic reconciliation worker,
  which re-queues missed screenshots and retries uploads without a
  foreground service, so nothing is lost even if the observer stays dead.

## Media access

- Android 13+ (`READ_MEDIA_IMAGES` + `READ_MEDIA_VIDEO`), Android 10–12
  (`READ_EXTERNAL_STORAGE`): scoped MediaStore reads only. The app never
  requests `MANAGE_EXTERNAL_STORAGE` or broad legacy storage.
- Android 14+ partial access ("Select photos"): the app declares
  `READ_MEDIA_VISUAL_USER_SELECTED`, so a partial choice is reported as
  partial (instead of a temporary full grant that is revoked when the app
  goes to the background). Setup and the Home screen detect it and send the
  user to app settings to choose "Allow all".
- Screenshots are identified by `RELATIVE_PATH` (primarily
  `Pictures/Screenshots`, plus manufacturer variants and user-selected
  folders) with a filename fallback (`Screenshot…`).
- Screen recordings are identified in `MediaStore.Video` by
  manufacturer-specific folders (`Movies/Screen records` on Pixel,
  `Movies/Screen recordings` on Samsung, `Movies/ScreenRecorder` on
  Xiaomi/Oppo, …) with a filename fallback (`Screen_Recording…`,
  `screenrecording…`, `screencast…`) for OEMs that save directly into
  `Movies/`.
- Recordings are streamed only after `IS_PENDING` becomes 0 — i.e. after
  the screen recorder has finished writing the file.
- Files are streamed from `content://` URIs; media is never loaded into
  memory as bitmaps/frames during transfer.

## Background execution

- **Battery optimization**: first-run setup opens the system battery
  optimization list (`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`) so the
  user can pick ShotSync → "Don't optimize". The direct-request dialog
  (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) is not used because Google Play
  restricts that permission. The foreground service already keeps
  monitoring alive under Doze. Some manufacturers still kill the process
  when the screen is off, which is what the exemption guards against.
- **Manufacturer-specific battery settings**: MIUI, EMUI, ColorOS, One UI
  and others have their own background restrictions ("auto-start",
  "protected apps", "unrestricted battery"). See troubleshooting.md.
- **ContentObserver reliability**: observer events can be dropped when the
  process is killed or under memory pressure. ShotSync therefore runs a
  reconciliation scan at app start, when monitoring starts, periodically
  (every 15 min via WorkManager), on network changes, and manually ("Scan
  for missed screenshots").
- **IS_PENDING**: files that are still being written are skipped and picked
  up on the next pass; the observer waits up to 30s for completion.

## Edge-to-edge, backup, network config

- targetSdk 36 enforces edge-to-edge; `MainActivity` calls
  `enableEdgeToEdge()` and pads content to `safeDrawing` insets.
- `allowBackup=false` plus `data_extraction_rules.xml`: nothing goes to
  Google cloud backup or device-to-device transfer (tokens are Keystore-bound).
- `network_security_config.xml`: no cleartext; system CAs only. The Mac's
  self-signed certificate is pinned at runtime by `PinningTrustManager`
  (its fingerprint is only known after pairing).

## Notifications

- Android 13+ requires the runtime `POST_NOTIFICATIONS` permission; setup
  requests it.
- The persistent "Shot Sync is monitoring for screenshots" notification is
  low-priority and non-interrupting.

## TLS

TLS 1.3 minimum on both ends; certificate pinning by SHA-256 fingerprint.
No global certificate-validation bypass (`InsecureSkipVerify` never used in
the app).
