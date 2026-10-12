### signaldock-api24 (API 24) - burst: **pass**

- Device: Google Android SDK built for arm64, Android 7.0 (API 24), arm64-v8a. Build: debug androidTest APK: SDK library debug variant, debuggable, no R8/minify (ADR-0014).
- Injection: target 100 events/s, actual 100.0 events/s over 9.99 s (1000 of 1000 calls); max calls in flight 3; start lateness {'p95': 3.3, 'max': 4.3}; event size 1022 bytes.
- track() completion (ms): p50 1.18, p95 2.46, max 26.17 (n=1000).
- Errors: track() not ok 0; SDK WARN/ERROR lines 0; last delivery error None.
- Reconciliation with server DB: tracked ok 1000, server rows 1000, missing 0, not tracked 0, duplicate rows 0; sent on the wire 1000 events in 364 batches, IDs sent more than once 0, batches not 200: 0, ACK statuses {'accepted': 1000}.
- Queue drain: {'seconds': 0.06, 'queued_after': 0, 'queued_at_injection_end': 19}.
- Files (bytes): {'at_injection_end': {'installation-id': 36, 'queue.db': 45056, 'queue.db-shm': 32768, 'queue.db-wal': 432632}, 'after_drain': {'installation-id': 36, 'queue.db': 45056, 'queue.db-shm': 32768, 'queue.db-wal': 432632}}.
- Memory (KiB): {'pss_after_init_to_injection_end': 7549, 'pss_after_init_to_after_drain': 7565, 'heap_after_init_to_after_drain': 3501, 'pss_absolute': {'before_init': 19406, 'after_init': 22901, 'injection_end': 30450, 'after_drain': 30466}}.
- Host: Apple M2 Pro, 12 CPUs, load average before { 10.43 9.02 7.71 }, after { 9.46 8.85 7.67 }. Run 2026-10-12T16:10:23Z to 2026-10-12T16:10:34Z, git 5123400 (dirty).
