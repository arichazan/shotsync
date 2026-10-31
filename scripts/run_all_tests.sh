#!/usr/bin/env bash
# Runs every automated test in the repo: Go unit tests + Android unit tests.
set -euo pipefail
cd "$(dirname "$0")/.."

echo "==> Go unit tests"
(cd mac && go vet ./... && go test ./...)

echo "==> Android unit tests"
if [ -n "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ] || [ -f android/local.properties ]; then
  (cd android && ./gradlew :app:testDebugUnitTest --console=plain -q)
else
  echo "SKIP: no Android SDK (set ANDROID_HOME or android/local.properties); CI runs these"
fi

echo "==> Integration test (isolated receiver)"
./scripts/integration_test.sh

echo
echo "ALL TESTS PASSED"
