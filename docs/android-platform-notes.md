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

- **Battery optimization**: first-run setup asks the user to exclude
  ShotSync (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`). Without it,
  manufacturers may kill the process while the screen is off.
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

## Notifications

- Android 13+ requires the runtime `POST_NOTIFICATIONS` permission; setup
  requests it.
- The persistent "Shot Sync is monitoring for screenshots" notification is
  low-priority and non-interrupting.

## TLS

TLS 1.3 minimum on both ends; certificate pinning by SHA-256 fingerprint.
No global certificate-validation bypass (`InsecureSkipVerify` never used in
the app).
