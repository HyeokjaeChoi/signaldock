### signaldock-api24 (API 24) - normal: **pass**

- Device: Google Android SDK built for arm64, Android 7.0 (API 24), arm64-v8a. Build: debug androidTest APK: SDK library debug variant, debuggable, no R8/minify (ADR-0014).
- Injection: target 1 events/s, actual 1.0 events/s over 59.01 s (60 of 60 calls); max calls in flight 1; start lateness {'p95': 10.3, 'max': 11.0}; event size 1024 bytes.
- track() completion (ms): p50 2.36, p95 5.73, max 8.3 (n=60).
- Errors: track() not ok 0; SDK WARN/ERROR lines 0; last delivery error None.
- Reconciliation with server DB: tracked ok 60, server rows 60, missing 0, not tracked 0, duplicate rows 0; sent on the wire 60 events in 12 batches, IDs sent more than once 0, batches not 200: 0, ACK statuses {'accepted': 60}.
- Queue drain: {'seconds': 0.94, 'queued_after': 0, 'queued_at_injection_end': 6}.
- Files (bytes): {'at_injection_end': {'installation-id': 36, 'queue.db': 36864, 'queue.db-shm': 32768, 'queue.db-wal': 420272}, 'after_drain': {'installation-id': 36, 'queue.db': 36864, 'queue.db-shm': 32768, 'queue.db-wal': 420272}}.
- Memory (KiB): {'pss_after_init_to_injection_end': 6075, 'pss_after_init_to_after_drain': 6599, 'heap_after_init_to_after_drain': 4440, 'pss_absolute': {'before_init': 18537, 'after_init': 22373, 'injection_end': 28448, 'after_drain': 28972}}.
- Host: Apple M2 Pro, 12 CPUs, load average before { 14.30 8.14 7.25 }, after { 13.18 9.40 7.81 }. Run 2026-10-12T16:08:59Z to 2026-10-12T16:10:00Z, git 5123400 (dirty).
