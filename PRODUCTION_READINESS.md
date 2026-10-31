# ShotSync: production readiness

Branch: `production-ready` (based on `main` @ 96707c9)

## 1. Readiness score

**Before: 5/10.** The core worked and had tests, but there was no CI, no
release signing, no macOS release packaging, no privacy policy, a
Play-restricted permission, a silent Android 14 partial-photo-access
failure, and an unpadded edge-to-edge UI on Android 15+.

**After: 7.5/10.** CI builds and tests both halves. Release APK/AAB signing
is wired to secrets. Universal macOS builds and a Homebrew formula are
scripted. The Play compliance issues are fixed, and the privacy policy, Data
Safety sheet and listing are drafted. What remains is owner-only work
(certificates, Play account, closed test, graphics) plus on-device QA.

## 2. What was done

### Mac receiver (Go)
- `go vet ./...` clean; `go test ./...` and `go test -race ./...` pass locally.
- **Bug fix:** `internal/keychain` documented a file fallback that didn't
  exist. Without the `security` CLI, pairing returned HTTP 500. Added an
  owner-only (0600) `tokens.json` fallback that is used only when the
  Keychain CLI is unavailable, with a unit test. macOS still always uses the
  Keychain.
- `scripts/integration_test.sh`: falls back when `uuidgen` is missing. It
  now passes on Linux here as well as on macOS CI.
- `version` can now be set via `-ldflags "-X main.version=…"`.
- `scripts/release_mac.sh`: builds arm64 and amd64 with cgo (the menu bar
  needs AppKit, so it must build on macOS), merges them with `lipo`, and
  produces a tar.gz plus sha256. It codesigns (hardened runtime) and
  notarizes only when credentials are present in the environment.
- `homebrew/shotsync.rb`: tap formula template. `ruby -c` reports Syntax OK.
- `docs/release-mac.md`: how to codesign and notarize the binary, and a
  future `ShotSync.app` menu-bar bundle (with stapling).

### Android
- Fixes for Android 14–16 and Play policy (`AndroidManifest.xml`, `MonitorService.kt`, `MainActivity.kt`, `SetupScreen.kt`, `HomeScreen.kt`):
  - Declared `READ_MEDIA_VISUAL_USER_SELECTED`. Without it, on Android 14
    "Select photos" gave a temporary full grant that disappeared in the
    background, and monitoring stopped silently. Setup and Home now detect
    partial or missing access and link to app settings ("Allow all").
  - The foreground service starts with an explicit `specialUse` type
    (`ServiceCompat.startForeground`). If the system refuses the start, it
    stops cleanly instead of crashing, and `startForegroundService` failures
    are caught. The `specialUse` type plus its subtype property were already
    declared and are correct. `dataSync` would hit the 6 h/day limit and
    cannot start from BOOT_COMPLETED on Android 15+.
  - Removed `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` because Play restricts it.
    Setup now opens the battery-optimization list instead.
  - targetSdk is **36**, already set and kept. Play's yearly target-API rule
    requires at least 35, and 36 from its 2026 deadline. Confirm the date in
    the Play Console policy status. Added `enableEdgeToEdge()`
    and safe-drawing padding, because edge-to-edge is enforced.
  - `allowBackup=false` plus `data_extraction_rules.xml`, so no cloud backup
    or device transfer of Keystore-bound tokens.
  - `network_security_config.xml`: no cleartext and system CAs only. The
    self-signed Mac cert stays pinned at runtime by `PinningTrustManager`.
  - POST_NOTIFICATIONS on 13+ and READ_MEDIA_IMAGES/VIDEO were already
    requested correctly.
- Release signing comes from `SHOTSYNC_KEYSTORE_PATH/PASSWORD`,
  `SHOTSYNC_KEY_ALIAS` and `SHOTSYNC_KEY_PASSWORD`. If they are absent, the
  build is unsigned. `versionCode` comes from `SHOTSYNC_VERSION_CODE` (the CI
  run number).
- `.github/workflows/android.yml` (ubuntu-latest, Temurin 17, Gradle) runs
  the unit tests, then `assembleRelease` and `bundleRelease`, and uploads the
  APK and AAB.

### Verification
- Local: `scripts/run_all_tests.sh` passes. That covers Go vet and tests,
  and the integration test with all 9 checks. The Android step is skipped
  here because there is no Android SDK in this container, and CI runs it.
- CI android, success (unit tests + release APK/AAB):
  https://github.com/arichazan/shotsync/actions/runs/41211590430
- CI mac-receiver, success (Linux vet/race tests; macOS vet/tests, integration
  test, universal `release_mac.sh` build):
  https://github.com/arichazan/shotsync/actions/runs/41211374391

### Docs
`PRIVACY.md`, `docs/play-store/data-safety.md`, `docs/play-store/listing.md`,
`docs/play-store/closed-testing.md`, `docs/release-mac.md`, and updates to
`docs/android-platform-notes.md` and `docs/build-android.md`.

## 3. Remaining blockers

### Owner must do
1. **Play developer account** (if not yet): play.google.com/console → sign in
   → *Personal* → pay the US$25 fee → verify your identity and Android device.
2. **Upload key.** On your machine:
   `keytool -genkeypair -v -keystore shotsync-upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000`.
   Store it in a password manager and never commit it. Then go to
   GitHub → repo *Settings → Secrets and variables → Actions → New repository secret*
   and add `SHOTSYNC_KEYSTORE_BASE64` (`base64 -i shotsync-upload.jks`),
   `SHOTSYNC_KEYSTORE_PASSWORD`, `SHOTSYNC_KEY_ALIAS` (`upload`) and
   `SHOTSYNC_KEY_PASSWORD`. Re-run the *android* workflow and download the
   signed `.aab`.
3. **Create the app.** Play Console → *Create app* → name "ShotSync:
   Screenshots to Mac", App, Free → accept the declarations. Enrol in Play
   App Signing when you upload the first AAB.
4. **App content.** Privacy policy URL: publish `PRIVACY.md` at
   https://arichazan.github.io/apps/shotsync.html. Then fill in Data safety,
   Photo & video permissions, Foreground service (special use, with a demo
   video), App access (reviewer instructions), Ads = No, Content rating and
   Target audience. The answers are in `docs/play-store/data-safety.md`.
5. **Store listing.** Paste the text from `docs/play-store/listing.md` and
   upload a 512² icon, a 1024×500 feature graphic and at least 2 phone
   screenshots.
6. **Closed test: 12+ testers for 14 days**, then *Apply for production*.
   The steps are in `docs/play-store/closed-testing.md`.
7. **macOS (optional, for direct downloads).** Join the Apple Developer
   Program, create a *Developer ID Application* certificate, store notarytool
   credentials, and run `scripts/release_mac.sh <ver>` on a Mac. Then create
   GitHub release `v<ver>`, upload the tarball, fill in the
   `homebrew/shotsync.rb` version and sha256, and create the
   `arichazan/homebrew-tap` repo. See `docs/release-mac.md`.

### Engineering left
- On-device QA on Android 14/15/16 (Pixel + Samsung):
  - partial-access flow
  - FGS restart after reboot
  - battery-settings screen on OEM skins
  - edge-to-edge layout in the Settings overlay and dialogs
- Instrumented/UI tests (only JVM unit tests exist).
- The Go module path is `example.com/shotsync/mac`. Consider renaming it to
  `github.com/arichazan/shotsync/mac` before others import it (cosmetic for
  binaries).
- The Mac stores tokens via `security add-generic-password -w <token>`, which
  briefly exposes the token in the process list to the same user. Consider
  the Security framework via cgo.
- The menu bar runs as `shotsync menu`, with no `.app` bundle or login item
  for it yet.
- The CI mac job does not sign. Add certificate import from secrets when the
  certificates exist.

## 4. Play submission checklist
- [ ] Signed AAB from CI (secrets set), versionCode increasing
- [ ] targetSdk 36 ✔ · minSdk 29 ✔
- [ ] Privacy policy live at the landing URL
- [ ] Data safety form (`docs/play-store/data-safety.md`)
- [ ] Photo & video permission declaration
- [ ] Foreground service *special use* declaration + video
- [ ] App access instructions for reviewers (receiver install + QR)
- [ ] Content rating, target audience, ads = no
- [ ] Listing text, icon, feature graphic, screenshots
- [ ] Closed test ≥12 testers × 14 days → production access granted
- [ ] Production release with staged rollout
