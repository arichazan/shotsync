#!/usr/bin/env bash
# Latency performance test (PRD section 11).
# Spins up an isolated receiver with a high rate limit and measures
# upload latency end-to-end: median, p95, max.
#
# Usage:
#   ./scripts/perf_test.sh [-n 100] [-s 3000000]
set -euo pipefail
cd "$(dirname "$0")/.."

N=100
SIZE=3000000
PORT=47998

while getopts "n:s:" opt; do
  case $opt in
    n) N="$OPTARG" ;;
    s) SIZE="$OPTARG" ;;
    *) echo "usage: $0 [-n files] [-s bytes]"; exit 2 ;;
  esac
done

WORK=$(mktemp -d /tmp/shotsync-perf.XXXXXX)
trap 'kill $PID 2>/dev/null; rm -rf "$WORK"' EXIT

echo "==> building receiver + perfbench"
(cd mac && go build -o "$WORK/shotsync" ./cmd/shotsync && go build -o /tmp/shotsync-perfbench ./cmd/perfbench)

echo "==> starting receiver on port $PORT (rate limit disabled for bench)"
cat > "$WORK/config.json" <<EOF
{"port":$PORT,"bind_addr":"127.0.0.1","device_name":"Perf Mac","dest_dir":"$WORK/dest","max_size_mb":100,"paused":false,"log_level":"error","rate_limit_per_min":1000000}
EOF
SHOTSYNC_HOME="$WORK" "$WORK/shotsync" serve >"$WORK/serve.log" 2>&1 &
PID=$!
sleep 1

URL="https://127.0.0.1:$PORT"
SECRET=$(curl -sk -X POST "$URL/api/v1/admin/pairing-issue" \
  | python3 -c "import json,sys;print(json.load(sys.stdin)['pairing_secret'])")

echo "==> running $N uploads of $SIZE bytes against $URL"
/tmp/shotsync-perfbench -url "$URL" -pairing-secret "$SECRET" -files "$N" -size "$SIZE" -burst 5
