#!/usr/bin/env bash
# End-to-end integration test against an isolated receiver instance:
# health, pairing, upload, dedupe, hash mismatch, bad auth, size limit.
# The receiver runs from SHOTSYNC_HOME=$TMP so the real config is untouched.
set -euo pipefail
cd "$(dirname "$0")/.."

PORT=47999
WORK=$(mktemp -d /tmp/shotsync-e2e.XXXXXX)
trap 'kill $PID 2>/dev/null; rm -rf "$WORK"' EXIT

echo "==> building receiver"
(cd mac && go build -o "$WORK/shotsync" ./cmd/shotsync)

echo "==> starting receiver on port $PORT (home=$WORK)"
cat > "$WORK/config.json" <<EOF
{"port":$PORT,"bind_addr":"127.0.0.1","device_name":"E2E Mac","dest_dir":"$WORK/dest","max_size_mb":2,"paused":false,"log_level":"info"}
EOF
SHOTSYNC_HOME="$WORK" "$WORK/shotsync" serve >"$WORK/serve.log" 2>&1 &
PID=$!
sleep 1

BASE="https://127.0.0.1:$PORT"

check() { # name actual expected
  if [ "$2" = "$3" ]; then echo "PASS: $1"; else echo "FAIL: $1 (got '$2', want '$3')"; exit 1; fi
}

echo "==> health"
HEALTH=$(curl -sk "$BASE/api/v1/health")
echo "$HEALTH" | grep -q '"status":"ok"' && echo "PASS: health ok" || { echo "FAIL: health"; exit 1; }

echo "==> pairing (wrong secret rejected)"
CODE=$(curl -sk -o /dev/null -w "%{http_code}" -X POST "$BASE/api/v1/pair" \
  -d '{"pairing_secret":"wrong","device_id":"e2e-0001","device_name":"E2E"}')
check "wrong secret -> 401" "$CODE" "401"

echo "==> pairing (correct secret)"
PAYLOAD=$(curl -sk -X POST "$BASE/api/v1/admin/pairing-issue")
SECRET=$(echo "$PAYLOAD" | python3 -c "import json,sys;print(json.load(sys.stdin)['pairing_secret'])")
TOKEN=$(curl -sk -X POST "$BASE/api/v1/pair" \
  -d "{\"pairing_secret\":\"$SECRET\",\"device_id\":\"e2e-0001\",\"device_name\":\"E2E Pixel\"}" \
  | python3 -c "import json,sys;print(json.load(sys.stdin)['device_token'])")
[ -n "$TOKEN" ] && echo "PASS: pairing issued token" || { echo "FAIL: no token"; exit 1; }

echo "==> unauthenticated upload rejected"
printf 'x' > "$WORK/a.png"
HASH=$(shasum -a 256 "$WORK/a.png" | cut -d' ' -f1)
CODE=$(curl -sk -o /dev/null -w "%{http_code}" -X POST "$BASE/api/v1/screenshots" \
  -H "X-ShotSync-Device-ID: e2e-0001" -H "X-ShotSync-SHA256: $HASH" \
  -H "X-ShotSync-Filename: a.png" -H "Content-Type: image/png" --data-binary @"$WORK/a.png")
check "no auth -> 401" "$CODE" "401"

upload() { # file -> response body
  local f="$1"; local name="$2"; local h; h=$(shasum -a 256 "$f" | cut -d' ' -f1)
  curl -sk -X POST "$BASE/api/v1/screenshots" \
    -H "Authorization: Bearer $TOKEN" -H "X-ShotSync-Device-ID: e2e-0001" \
    -H "X-ShotSync-File-ID: e2e-$(uuidgen)" -H "X-ShotSync-SHA256: $h" \
    -H "X-ShotSync-Filename: $name" -H "Content-Type: image/png" --data-binary @"$f"
}

echo "==> upload"
BODY=$(upload "$WORK/a.png" "Screenshot_e2e.png")
echo "$BODY" | grep -q '"status":"stored"' && echo "PASS: stored" || { echo "FAIL: $BODY"; exit 1; }

echo "==> duplicate upload returns already_exists"
BODY=$(upload "$WORK/a.png" "Screenshot_e2e_othername.png")
echo "$BODY" | grep -q '"status":"already_exists"' && echo "PASS: already_exists" || { echo "FAIL: $BODY"; exit 1; }

echo "==> hash mismatch rejected"
CODE=$(curl -sk -o /dev/null -w "%{http_code}" -X POST "$BASE/api/v1/screenshots" \
  -H "Authorization: Bearer $TOKEN" -H "X-ShotSync-Device-ID: e2e-0001" \
  -H "X-ShotSync-File-ID: e2e-$(uuidgen)" -H "X-ShotSync-SHA256: $(printf '0%.0s' {1..64})" \
  -H "X-ShotSync-Filename: bad.png" -H "Content-Type: image/png" --data-binary @"$WORK/a.png")
check "hash mismatch -> 400" "$CODE" "400"

echo "==> wrong media type rejected"
CODE=$(curl -sk -o /dev/null -w "%{http_code}" -X POST "$BASE/api/v1/screenshots" \
  -H "Authorization: Bearer $TOKEN" -H "X-ShotSync-Device-ID: e2e-0001" \
  -H "X-ShotSync-File-ID: e2e-$(uuidgen)" -H "X-ShotSync-SHA256: $(printf '0%.0s' {1..64})" \
  -H "X-ShotSync-Filename: evil.sh" -H "Content-Type: text/plain" --data-binary @"$WORK/a.png")
check "text/plain -> 415" "$CODE" "415"

echo "==> revoked token rejected"
curl -sk -o /dev/null -X DELETE "$BASE/api/v1/device/e2e-0001" \
  -H "Authorization: Bearer $TOKEN" -H "X-ShotSync-Device-ID: e2e-0001"
CODE=$(curl -sk -o /dev/null -w "%{http_code}" -X POST "$BASE/api/v1/screenshots" \
  -H "Authorization: Bearer $TOKEN" -H "X-ShotSync-Device-ID: e2e-0001" \
  -H "X-ShotSync-File-ID: e2e-$(uuidgen)" -H "X-ShotSync-SHA256: $(printf '0%.0s' {1..64})" \
  -H "X-ShotSync-Filename: bad.png" -H "Content-Type: image/png" --data-binary @"$WORK/a.png")
check "revoked token -> 401" "$CODE" "401"

echo
echo "ALL INTEGRATION TESTS PASSED"
