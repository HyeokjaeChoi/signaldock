### signaldock-api37 (API 37) - burst: **pass**

- Device: Google sdk_gphone16k_arm64, Android 17 (API 37), arm64-v8a. Build: debug androidTest APK: SDK library debug variant, debuggable, no R8/minify (ADR-0014).
- Injection: target 100 events/s, actual 99.99 events/s over 9.99 s (1000 of 1000 calls); max calls in flight 4; start lateness {'p95': 2.7, 'max': 6.4}; event size 1022 bytes.
- track() completion (ms): p50 0.85, p95 2.29, max 52.14 (n=1000).
- Errors: track() not ok 0; SDK WARN/ERROR lines 0; last delivery error None.
- Reconciliation with server DB: tracked ok 1000, server rows 1000, missing 0, not tracked 0, duplicate rows 0; sent on the wire 1000 events in 180 batches, IDs sent more than once 0, batches not 200: 0, ACK statuses {'accepted': 1000}.
- Queue drain: {'seconds': 0.06, 'queued_after': 0, 'queued_at_injection_end': 16}.
- Files (bytes): {'at_injection_end': {'installation-id': 36, 'queue.db': 36864, 'queue.db-shm': 32768, 'queue.db-wal': 502672}, 'after_drain': {'installation-id': 36, 'queue.db': 36864, 'queue.db-shm': 32768, 'queue.db-wal': 502672}}.
- Memory (KiB): {'pss_after_init_to_injection_end': 3934, 'pss_after_init_to_after_drain': 2702, 'heap_after_init_to_after_drain': 16, 'pss_absolute': {'before_init': 38209, 'after_init': 38582, 'injection_end': 42516, 'after_drain': 41284}}.
- Host: Apple M2 Pro, 12 CPUs, load average before { 5.35 5.92 6.98 }, after { 6.02 6.06 7.01 }. Run 2026-10-12T16:24:41Z to 2026-10-12T16:24:53Z, git edaa14c (dirty).
