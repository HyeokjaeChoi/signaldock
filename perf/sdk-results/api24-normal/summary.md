### signaldock-api24 (API 24) - normal: **pass**

- Device: Google Android SDK built for arm64, Android 7.0 (API 24), arm64-v8a. Build: debug androidTest APK: SDK library debug variant, debuggable, no R8/minify (ADR-0014).
- Injection: target 1 events/s, actual 1.0 events/s over 59.01 s (60 of 60 calls); max calls in flight 1; start lateness {'p95': 9.8, 'max': 10.3}; event size 1024 bytes.
- track() completion (ms): p50 1.87, p95 3.8, max 7.08 (n=60).
- Errors: track() not ok 0; SDK WARN/ERROR lines 0; last delivery error None.
- Reconciliation with server DB: tracked ok 60, server rows 60, missing 0, not tracked 0, duplicate rows 0; sent on the wire 60 events in 12 batches, IDs sent more than once 0, batches not 200: 0, ACK statuses {'accepted': 60}.
- Queue drain: {'seconds': 0.92, 'queued_after': 0, 'queued_at_injection_end': 7}.
- Files (bytes): {'at_injection_end': {'installation-id': 36, 'queue.db': 36864, 'queue.db-shm': 32768, 'queue.db-wal': 420272}, 'after_drain': {'installation-id': 36, 'queue.db': 36864, 'queue.db-shm': 32768, 'queue.db-wal': 420272}}.
- Memory (KiB): {'pss_after_init_to_injection_end': 6596, 'pss_after_init_to_after_drain': 7032, 'heap_after_init_to_after_drain': 4472, 'pss_absolute': {'before_init': 18515, 'after_init': 22401, 'injection_end': 28997, 'after_drain': 29433}}.
- Host: Apple M2 Pro, 12 CPUs, load average before { 5.30 8.34 8.20 }, after { 4.47 7.56 7.92 }. Run 2026-10-12T16:17:23Z to 2026-10-12T16:18:24Z, git 5b369f4 (dirty).
