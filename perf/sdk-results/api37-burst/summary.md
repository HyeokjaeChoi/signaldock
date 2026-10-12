### signaldock-api37 (API 37) - burst: **pass**

- Device: Google sdk_gphone16k_arm64, Android 17 (API 37), arm64-v8a. Build: debug androidTest APK: SDK library debug variant, debuggable, no R8/minify (ADR-0014).
- Injection: target 100 events/s, actual 100.0 events/s over 9.99 s (1000 of 1000 calls); max calls in flight 3; start lateness {'p95': 2.5, 'max': 9.5}; event size 1024 bytes.
- track() completion (ms): p50 0.84, p95 2.04, max 28.65 (n=1000).
- Errors: track() not ok 0; SDK WARN/ERROR lines 0; last delivery error None.
- Reconciliation with server DB: tracked ok 1000, server rows 1000, missing 0, not tracked 0, duplicate rows 0; sent on the wire 1000 events in 202 batches, IDs sent more than once 0, batches not 200: 0, ACK statuses {'accepted': 1000}.
- Queue drain: {'seconds': 0.06, 'queued_after': 0, 'queued_at_injection_end': 18}.
- Files (bytes): {'at_injection_end': {'installation-id': 36, 'queue.db': 45056, 'queue.db-shm': 32768, 'queue.db-wal': 449112}, 'after_drain': {'installation-id': 36, 'queue.db': 45056, 'queue.db-shm': 32768, 'queue.db-wal': 449112}}.
- Memory (KiB): {'pss_after_init_to_injection_end': 3770, 'pss_after_init_to_after_drain': 2506, 'heap_after_init_to_after_drain': 0, 'pss_absolute': {'before_init': 39267, 'after_init': 38749, 'injection_end': 42519, 'after_drain': 41255}}.
- Host: Apple M2 Pro, 12 CPUs, load average before { 8.01 7.33 7.33 }, after { 8.32 7.42 7.36 }. Run 2026-10-12T15:57:22Z to 2026-10-12T15:57:34Z, git 6bd96af (dirty).
