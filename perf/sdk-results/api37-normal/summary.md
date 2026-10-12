### signaldock-api37 (API 37) - normal: **pass**

- Device: Google sdk_gphone16k_arm64, Android 17 (API 37), arm64-v8a. Build: debug androidTest APK: SDK library debug variant, debuggable, no R8/minify (ADR-0014).
- Injection: target 1 events/s, actual 1.0 events/s over 59.0 s (60 of 60 calls); max calls in flight 1; start lateness {'p95': 3.6, 'max': 4.3}; event size 1024 bytes.
- track() completion (ms): p50 1.85, p95 5.37, max 18.95 (n=60).
- Errors: track() not ok 0; SDK WARN/ERROR lines 0; last delivery error None.
- Reconciliation with server DB: tracked ok 60, server rows 60, missing 0, not tracked 0, duplicate rows 0; sent on the wire 60 events in 12 batches, IDs sent more than once 0, batches not 200: 0, ACK statuses {'accepted': 60}.
- Queue drain: {'seconds': 2.1, 'queued_after': 0, 'queued_at_injection_end': 7}.
- Files (bytes): {'at_injection_end': {'installation-id': 36, 'queue.db': 36864, 'queue.db-shm': 32768, 'queue.db-wal': 424392}, 'after_drain': {'installation-id': 36, 'queue.db': 36864, 'queue.db-shm': 32768, 'queue.db-wal': 424392}}.
- Memory (KiB): {'pss_after_init_to_injection_end': 2746, 'pss_after_init_to_after_drain': 2218, 'heap_after_init_to_after_drain': -224, 'pss_absolute': {'before_init': 38296, 'after_init': 38581, 'injection_end': 41327, 'after_drain': 40799}}.
- Host: Apple M2 Pro, 12 CPUs, load average before { 5.29 5.58 6.40 }, after { 6.91 5.93 6.46 }. Run 2026-10-12T16:07:03Z to 2026-10-12T16:08:06Z, git 5123400 (dirty).
