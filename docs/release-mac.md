# Releasing the macOS receiver

The receiver, CLI, launchd agent and menu-bar UI are all the same Go binary
(`shotsync`; the menu bar is `shotsync menu`). A release is one universal
Mach-O binary plus a minimal `ShotSync.app` bundle that wraps the same binary
for the menu bar.

## 1. Build (macOS only)

```bash
./scripts/release_mac.sh 0.1.0
# -> dist/shotsync-0.1.0-darwin-universal.tar.gz (+ .sha256)
#      shotsync-0.1.0/shotsync, shotsync-0.1.0/ShotSync.app, LICENSE, README.md
# -> dist/ShotSync-0.1.0.app.zip (+ .sha256)
```

The script builds `darwin/arm64` and `darwin/amd64` with cgo (the menu bar
uses AppKit through `fyne.io/systray`, so it cannot be cross-compiled from
Linux), merges them with `lipo`, stamps the version via
`-ldflags "-X main.version=…"`, and packages LICENSE + README.

CI (`.github/workflows/mac.yml`) runs the same script unsigned on
`macos-latest` for every push, so the build is always verified.

## 2. Codesign (when a Developer ID certificate exists)

Prerequisites (owner): a paid Apple Developer Program membership and a
**Developer ID Application** certificate in the login keychain
(Xcode → Settings → Accounts → Manage Certificates → + → Developer ID Application).

```bash
security find-identity -v -p codesigning     # copy the exact identity string
export MACOS_SIGN_IDENTITY="Developer ID Application: Ari Chazan (TEAMID)"
./scripts/release_mac.sh 0.1.0
```

The script signs with `--options runtime --timestamp` (hardened runtime +
secure timestamp, both required for notarization) and identifier
`com.shotsync.receiver`. No extra entitlements are needed: the binary does not
use JIT, unsigned memory or the camera/microphone. Network server access does
not require an entitlement outside the App Sandbox.

Keychain note: device tokens are stored as generic passwords under service
`com.shotsync.receiver` through the Security framework (`SecItemAdd` /
`SecItemUpdate` / `SecItemCopyMatching` / `SecItemDelete`), so a token never
appears on a command line. Items are owned by the binary that wrote them.
Items written by older builds (which used the `security` CLI), or by an
earlier unsigned build, are read once through `security find-generic-password`
(the secret travels only over its stdout pipe) and re-written by the current
binary. A signed release keeps access across upgrades because its designated
requirement stays the same. If macOS still asks for Keychain access, choose
"Always Allow".

## 3. Notarize

Create credentials once (app-specific password from appleid.apple.com):

```bash
xcrun notarytool store-credentials shotsync-notary \
  --apple-id you@example.com --team-id TEAMID --password abcd-efgh-ijkl-mnop
export MACOS_NOTARY_PROFILE=shotsync-notary
./scripts/release_mac.sh 0.1.0
```

In CI use `MACOS_NOTARY_APPLE_ID`, `MACOS_NOTARY_TEAM_ID`,
`MACOS_NOTARY_PASSWORD` secrets instead of a profile (and import the
certificate from a base64 `.p12` secret into a temporary keychain).

A bare command-line binary cannot be stapled; Gatekeeper checks the
notarization ticket online on first launch. Homebrew installs are not
quarantined, so the formula works even for unsigned builds, but signed +
notarized binaries are required for direct downloads to open without the
"unidentified developer" warning.

## 4. `ShotSync.app` (menu-bar bundle)

`release_mac.sh` builds `ShotSync.app` automatically:

- `Contents/MacOS/shotsync`: the same universal binary. Launched with no
  arguments from inside a `.app` (Finder, Spotlight, login item) it runs
  `shotsync menu` instead of `serve`.
- `Contents/Info.plist`: from `mac/packaging/Info.plist`, with
  `CFBundleIdentifier=com.shotsync.menubar`, `LSUIElement=true` (no Dock
  icon), and the version and `LSMinimumSystemVersion` filled in.
- `Contents/Resources/ShotSync.icns`: rendered by `mac/cmd/mkicon` (the
  same blue rounded square as the menu-bar icon), converted with `iconutil`.

With `MACOS_SIGN_IDENTITY` set, the script signs the bundle with the hardened
runtime. With notary credentials it notarizes the CLI and the app in one
submission and staples the app (a bare CLI binary cannot be stapled).
Without them, it ad-hoc signs the bundle so `codesign --verify` passes. CI
checks the bundle on every push: plist lint, `LSUIElement`, icon present,
both architectures, signature verification, and login-item creation.

Manual check on a signed build:

```bash
spctl --assess --type execute -vv dist/shotsync-0.1.0/ShotSync.app
```

## 5. Homebrew tap

`homebrew/shotsync.rb` is a template. After uploading the tarball to a
GitHub release `v<version>`, fill in `version` and `sha256` and commit it to
a tap repository (`arichazan/homebrew-tap`, path `Formula/shotsync.rb`).
Users then install with `brew install arichazan/tap/shotsync`.
