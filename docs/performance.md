# Performance

Targets (PRD section 1):

- Same Wi-Fi, Mac awake: median < 2 s
- Tailscale: normally < 5 s
- No screenshot is silently lost

## Measurement

Latency is measured end-to-end on the Android side from MediaStore
detection timestamp to receiver acknowledgement, and is visible in the
diagnostics view:

```
Screenshot detected: 09:45:21.104
Upload started:     09:45:21.291
Upload completed:   09:45:21.704
Total latency:      600 ms
```

A benchmark harness measures upload latency against a receiver directly:

```bash
./scripts/perf_test.sh -n 100 -s 3000000
# files=100 size=3000000 bytes
# median / p95 / max (upload duration, detection time excluded)
```

Run the same harness over Tailscale by pointing the Android app's fallback
at the Mac and observing the diagnostics view, or by benchmarking the
receiver over the Tailscale interface.

## Design choices that keep latency low

- The observer triggers uploads immediately (debounced ~600 ms for burst
  coalescing), instead of waiting for periodic work.
- The queue starts the upload as soon as the file is confirmed complete;
  hashing is streamed from the content URI (no memory copies).
- Raw image bytes are sent as the request body; no base64 or JSON wrapping.
- The receiver streams directly to disk while hashing; the acknowledgement
  is sent immediately after rename, so the client measures real delivery.
- `already_exists` responses skip the body entirely for deduplicated files.

## Notes

- First upload after idle may include TCP/TLS setup (~50–150 ms).
- Five screenshots taken quickly serialize on the single-flight queue
  (oldest first, one at a time) — the median of the first transfer is the
  headline number; the others queue behind it.
- Large scrolling screenshots are dominated by Wi-Fi throughput; the
  pipeline itself adds only hashing + disk write time.
