# Releasing the macOS receiver

The receiver, CLI, launchd agent and menu-bar UI are all the same Go binary
(`shotsync`; the menu bar is `shotsync menu`). A release is therefore one
universal Mach-O binary in a tarball.

## 1. Build (macOS only)

```bash
./scripts/release_mac.sh 0.1.0
# -> dist/shotsync-0.1.0-darwin-universal.tar.gz (+ .sha256)
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

Keychain note: device tokens are stored with `security` under service
`com.shotsync.receiver`. After switching from an unsigned to a signed binary
macOS may ask once for Keychain access; choose "Always Allow".

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

## 4. If the menu bar is later shipped as `ShotSync.app`

Wrap the binary as `ShotSync.app/Contents/MacOS/shotsync` with an
`Info.plist` containing `CFBundleIdentifier=com.shotsync.menubar`,
`LSUIElement=true` (no Dock icon) and `CFBundleExecutable=shotsync`, then:

```bash
codesign --force --options runtime --timestamp --sign "$MACOS_SIGN_IDENTITY" ShotSync.app
ditto -c -k --keepParent ShotSync.app ShotSync.zip
xcrun notarytool submit ShotSync.zip --keychain-profile shotsync-notary --wait
xcrun stapler staple ShotSync.app        # .app bundles CAN be stapled
spctl --assess --type execute -vv ShotSync.app
```

Sign nested executables first (inside-out) rather than using `--deep`.

## 5. Homebrew tap

`homebrew/shotsync.rb` is a template. After uploading the tarball to a
GitHub release `v<version>`, fill in `version` and `sha256` and commit it to
a tap repository (`arichazan/homebrew-tap`, path `Formula/shotsync.rb`).
Users then install with `brew install arichazan/tap/shotsync`.
