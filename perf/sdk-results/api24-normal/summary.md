### signaldock-api24 (API 24) - normal: **pass**

- Device: Google Android SDK built for arm64, Android 7.0 (API 24), arm64-v8a. Build: debug androidTest APK: SDK library debug variant, debuggable, no R8/minify (ADR-0014).
- Injection: target 1 events/s, actual 1.0 events/s over 59.0 s (60 of 60 calls); max calls in flight 1; start lateness {'p95': 9.4, 'max': 10.4}; event size 1025 bytes.
- track() completion (ms): p50 1.83, p95 3.52, max 6.22 (n=60).
- Errors: track() not ok 0; SDK WARN/ERROR lines 0; last delivery error None.
- Reconciliation with server DB: tracked ok 60, server rows 60, missing 0, not tracked 0, duplicate rows 0; sent on the wire 60 events in 9 batches, IDs sent more than once 0, batches not 200: 0, ACK statuses {'accepted': 60}.
- Queue drain: {'seconds': 0.99, 'queued_after': 0, 'queued_at_injection_end': 7}.
- Files (bytes): {'at_injection_end': {'installation-id': 36, 'queue.db': 36864, 'queue.db-shm': 32768, 'queue.db-wal': 420272}, 'after_drain': {'installation-id': 36, 'queue.db': 36864, 'queue.db-shm': 32768, 'queue.db-wal': 420272}}.
- Memory (KiB): {'pss_after_init_to_injection_end': 5727, 'pss_after_init_to_after_drain': 6335, 'heap_after_init_to_after_drain': 4273, 'pss_absolute': {'before_init': 18533, 'after_init': 22318, 'injection_end': 28045, 'after_drain': 28653}}.
- Host: Apple M2 Pro, 12 CPUs, load average before { 8.56 7.56 7.41 }, after { 6.45 7.10 7.24 }. Run 2026-10-12T15:58:12Z to 2026-10-12T15:59:13Z, git 6bd96af (dirty).
