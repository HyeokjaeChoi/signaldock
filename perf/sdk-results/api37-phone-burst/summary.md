### SM-F766N (physical) (API 37) - burst, debug build: **pass**

- Device: samsung SM-F766N, Android 17 (API 37), arm64-v8a. Build: debug androidTest APK: SDK library debug variant, debuggable, no R8/minify (ADR-0014).
- Injection: target 100 events/s, actual 100.03 events/s over 9.99 s (1000 of 1000 calls); max calls in flight 8; start lateness {'p95': 3.4, 'max': 14.9}; event size 1022 bytes.
- track() completion (ms): p50 9.02, p95 18.02, max 93.16 (n=1000).
- Errors: track() not ok 0; SDK WARN/ERROR lines 0; last delivery error None.
- Reconciliation with server DB: tracked ok 1000, server rows 1000, missing 0, not tracked 0, duplicate rows 0; sent on the wire 1000 events in 89 batches, IDs sent more than once 0, batches not 200: 0, ACK statuses {'accepted': 1000}.
- Queue drain: {'seconds': 0.15, 'queued_after': 0, 'queued_at_injection_end': 13}.
- Files (bytes): {'at_injection_end': {'installation-id': 36, 'queue.db': 69632, 'queue.db-shm': 32768, 'queue.db-wal': 453232}, 'after_drain': {'installation-id': 36, 'queue.db': 69632, 'queue.db-shm': 32768, 'queue.db-wal': 453232}}.
- Memory (KiB): {'pss_after_init_to_injection_end': 20923, 'pss_after_init_to_after_drain': 4195, 'heap_after_init_to_after_drain': 1036, 'pss_absolute': {'before_init': 36828, 'after_init': 36195, 'injection_end': 57118, 'after_drain': 40390}}.
- Host: Apple M2 Pro, 12 CPUs, load average before { 11.12 10.43 7.26 }, after { 9.63 10.13 7.20 }. Run 2026-10-24T13:45:49Z to 2026-10-24T13:46:02Z, git 049f7ed.
