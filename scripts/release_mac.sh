#!/usr/bin/env bash
# Builds a universal (arm64 + amd64) macOS release of the shotsync receiver,
# optionally codesigns + notarizes it, and packages it for a GitHub release /
# Homebrew tap.
#
# Must run on macOS: the menu-bar UI (fyne.io/systray) uses cgo + AppKit, so
# darwin binaries cannot be cross-compiled from Linux.
#
# Usage:
#   ./scripts/release_mac.sh [version]          # default: version from git tag or 0.1.0
#
# Optional signing (skipped gracefully when unset):
#   MACOS_SIGN_IDENTITY   "Developer ID Application: Name (TEAMID)"
#   MACOS_NOTARY_PROFILE  keychain profile created with
#                         `xcrun notarytool store-credentials <profile> ...`
#     or, instead of a profile (CI):
#   MACOS_NOTARY_APPLE_ID, MACOS_NOTARY_TEAM_ID, MACOS_NOTARY_PASSWORD (app-specific password)
#
# Output:
#   dist/shotsync-<version>-darwin-universal.tar.gz (+ .sha256)
#       shotsync-<version>/shotsync      CLI receiver (Homebrew installs this)
#       shotsync-<version>/ShotSync.app  menu-bar app (LSUIElement) wrapping `shotsync menu`
#   dist/ShotSync-<version>.app.zip (+ .sha256)  the app alone, for drag-to-Applications
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$(pwd)

if [ "$(uname -s)" != "Darwin" ]; then
  echo "error: release_mac.sh must run on macOS (cgo/AppKit for the menu bar)." >&2
  exit 1
fi

VERSION="${1:-$(git describe --tags --abbrev=0 2>/dev/null | sed 's/^v//' || true)}"
VERSION="${VERSION:-0.1.0}"
DIST="$ROOT/dist"
STAGE="$DIST/shotsync-$VERSION"
rm -rf "$STAGE" && mkdir -p "$STAGE"

LDFLAGS="-s -w -X main.version=$VERSION"
export CGO_ENABLED=1
export MACOSX_DEPLOYMENT_TARGET="${MACOSX_DEPLOYMENT_TARGET:-12.0}"

for arch in arm64 amd64; do
  echo "==> building darwin/$arch"
  (cd mac && GOOS=darwin GOARCH=$arch \
     CGO_CFLAGS="-arch $( [ $arch = amd64 ] && echo x86_64 || echo arm64 )" \
     CGO_LDFLAGS="-arch $( [ $arch = amd64 ] && echo x86_64 || echo arm64 )" \
     go build -trimpath -ldflags "$LDFLAGS" -o "$DIST/shotsync-$arch" ./cmd/shotsync)
done

echo "==> lipo -> universal"
lipo -create -output "$STAGE/shotsync" "$DIST/shotsync-arm64" "$DIST/shotsync-amd64"
rm -f "$DIST/shotsync-arm64" "$DIST/shotsync-amd64"
lipo -info "$STAGE/shotsync"
"$STAGE/shotsync" version

echo "==> ShotSync.app bundle"
APP="$STAGE/ShotSync.app"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$STAGE/shotsync" "$APP/Contents/MacOS/shotsync"
ICONSET="$DIST/ShotSync.iconset"
rm -rf "$ICONSET"
(cd mac && go run ./cmd/mkicon "$ICONSET")
iconutil -c icns -o "$APP/Contents/Resources/ShotSync.icns" "$ICONSET"
rm -rf "$ICONSET"
sed -e "s/__VERSION__/$VERSION/g" -e "s/__MIN_MACOS__/$MACOSX_DEPLOYMENT_TARGET/g" \
  mac/packaging/Info.plist > "$APP/Contents/Info.plist"
plutil -lint "$APP/Contents/Info.plist"
[ "$(/usr/libexec/PlistBuddy -c 'Print :LSUIElement' "$APP/Contents/Info.plist")" = "true" ]
printf 'APPL????' > "$APP/Contents/PkgInfo"

if [ -n "${MACOS_SIGN_IDENTITY:-}" ]; then
  echo "==> codesign (hardened runtime, secure timestamp)"
  codesign --force --options runtime --timestamp \
    --identifier com.shotsync.receiver \
    --sign "$MACOS_SIGN_IDENTITY" "$STAGE/shotsync"
  codesign --verify --strict --verbose=2 "$STAGE/shotsync"
  codesign --force --options runtime --timestamp \
    --sign "$MACOS_SIGN_IDENTITY" "$APP"
  codesign --verify --deep --strict --verbose=2 "$APP"
else
  echo "==> MACOS_SIGN_IDENTITY not set: skipping codesign (ad-hoc signing the app bundle)"
  codesign --force --sign - "$APP"
fi

cp LICENSE README.md "$STAGE/"
APPZIP="$DIST/ShotSync-$VERSION.app.zip"

if [ -n "${MACOS_SIGN_IDENTITY:-}" ] && { [ -n "${MACOS_NOTARY_PROFILE:-}" ] || [ -n "${MACOS_NOTARY_APPLE_ID:-}" ]; }; then
  echo "==> notarizing CLI + app (the app is stapled; a bare binary cannot be)"
  ZIP="$DIST/notarize-$VERSION.zip"
  NSTAGE="$DIST/notarize-$VERSION"
  rm -rf "$NSTAGE" && mkdir -p "$NSTAGE"
  cp "$STAGE/shotsync" "$NSTAGE/"
  ditto "$APP" "$NSTAGE/ShotSync.app"
  ditto -c -k --keepParent "$NSTAGE" "$ZIP"
  if [ -n "${MACOS_NOTARY_PROFILE:-}" ]; then
    xcrun notarytool submit "$ZIP" --keychain-profile "$MACOS_NOTARY_PROFILE" --wait
  else
    xcrun notarytool submit "$ZIP" --apple-id "$MACOS_NOTARY_APPLE_ID" \
      --team-id "$MACOS_NOTARY_TEAM_ID" --password "$MACOS_NOTARY_PASSWORD" --wait
  fi
  xcrun stapler staple "$APP"
  rm -rf "$ZIP" "$NSTAGE"
else
  echo "==> notarization skipped (needs MACOS_SIGN_IDENTITY + notary credentials)"
fi

TARBALL="$DIST/shotsync-$VERSION-darwin-universal.tar.gz"
(cd "$DIST" && tar -czf "$TARBALL" "shotsync-$VERSION")
(cd "$STAGE" && ditto -c -k --keepParent ShotSync.app "$APPZIP")

shasum -a 256 "$TARBALL" | tee "$TARBALL.sha256"
shasum -a 256 "$APPZIP" | tee "$APPZIP.sha256"
echo "Release artifacts: $TARBALL, $APPZIP"
