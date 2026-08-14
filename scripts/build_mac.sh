#!/usr/bin/env bash
# Builds the macOS receiver into build/.
set -euo pipefail
cd "$(dirname "$0")/.."

mkdir -p build
echo "==> Building shotsync receiver (arm64)"
(cd mac && go build -trimpath -o ../build/shotsync ./cmd/shotsync)
echo "==> Building perfbench"
(cd mac && go build -trimpath -o ../build/shotsync-perfbench ./cmd/perfbench)
ls -la build/
