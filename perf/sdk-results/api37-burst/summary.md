### signaldock-api37 (API 37) - burst: **pass**

- Device: Google sdk_gphone16k_arm64, Android 17 (API 37), arm64-v8a. Build: debug androidTest APK: SDK library debug variant, debuggable, no R8/minify (ADR-0014).
- Injection: target 100 events/s, actual 100.0 events/s over 9.99 s (1000 of 1000 calls); max calls in flight 4; start lateness {'p95': 2.8, 'max': 8.7}; event size 1022 bytes.
- track() completion (ms): p50 0.84, p95 1.98, max 33.67 (n=1000).
- Errors: track() not ok 0; SDK WARN/ERROR lines 0; last delivery error None.
- Reconciliation with server DB: tracked ok 1000, server rows 1000, missing 0, not tracked 0, duplicate rows 0; sent on the wire 1000 events in 201 batches, IDs sent more than once 0, batches not 200: 0, ACK statuses {'accepted': 1000}.
- Queue drain: {'seconds': 0.06, 'queued_after': 0, 'queued_at_injection_end': 20}.
- Files (bytes): {'at_injection_end': {'installation-id': 36, 'queue.db': 49152, 'queue.db-shm': 32768, 'queue.db-wal': 453232}, 'after_drain': {'installation-id': 36, 'queue.db': 49152, 'queue.db-shm': 32768, 'queue.db-wal': 453232}}.
- Memory (KiB): {'pss_after_init_to_injection_end': 3157, 'pss_after_init_to_after_drain': 2341, 'heap_after_init_to_after_drain': 0, 'pss_absolute': {'before_init': 39430, 'after_init': 38781, 'injection_end': 41938, 'after_drain': 41122}}.
- Host: Apple M2 Pro, 12 CPUs, load average before { 6.03 8.74 8.33 }, after { 5.56 8.55 8.27 }. Run 2026-10-12T16:16:56Z to 2026-10-12T16:17:08Z, git 5b369f4 (dirty).
