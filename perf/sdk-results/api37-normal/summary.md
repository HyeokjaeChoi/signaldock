### signaldock-api37 (API 37) - normal: **pass**

- Device: Google sdk_gphone16k_arm64, Android 17 (API 37), arm64-v8a. Build: debug androidTest APK: SDK library debug variant, debuggable, no R8/minify (ADR-0014).
- Injection: target 1 events/s, actual 1.0 events/s over 59.0 s (60 of 60 calls); max calls in flight 1; start lateness {'p95': 7.6, 'max': 11.2}; event size 1025 bytes.
- track() completion (ms): p50 2.34, p95 7.09, max 30.93 (n=60).
- Errors: track() not ok 0; SDK WARN/ERROR lines 0; last delivery error None.
- Reconciliation with server DB: tracked ok 60, server rows 60, missing 0, not tracked 0, duplicate rows 0; sent on the wire 60 events in 11 batches, IDs sent more than once 0, batches not 200: 0, ACK statuses {'accepted': 60}.
- Queue drain: {'seconds': 1.51, 'queued_after': 0, 'queued_at_injection_end': 7}.
- Files (bytes): {'at_injection_end': {'installation-id': 36, 'queue.db': 36864, 'queue.db-shm': 32768, 'queue.db-wal': 424392}, 'after_drain': {'installation-id': 36, 'queue.db': 36864, 'queue.db-shm': 32768, 'queue.db-wal': 424392}}.
- Memory (KiB): {'pss_after_init_to_injection_end': 3310, 'pss_after_init_to_after_drain': 2206, 'heap_after_init_to_after_drain': -48, 'pss_absolute': {'before_init': 39220, 'after_init': 38637, 'injection_end': 41947, 'after_drain': 40843}}.
- Host: Apple M2 Pro, 12 CPUs, load average before { 7.61 7.17 7.28 }, after { 6.98 7.08 7.24 }. Run 2026-10-12T15:55:56Z to 2026-10-12T15:56:58Z, git 6bd96af (dirty).
