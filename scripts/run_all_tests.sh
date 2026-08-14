#!/usr/bin/env bash
# Runs every automated test in the repo: Go unit tests + Android unit tests.
set -euo pipefail
cd "$(dirname "$0")/.."

echo "==> Go unit tests"
(cd mac && go vet ./... && go test ./...)

echo "==> Android unit tests"
(cd android && ./gradlew :app:testDebugUnitTest --console=plain -q)

echo "==> Integration test (isolated receiver)"
./scripts/integration_test.sh

echo
echo "ALL TESTS PASSED"
