### SM-F766N (physical) (API 37) - normal, debug build: **pass**

- Device: samsung SM-F766N, Android 17 (API 37), arm64-v8a. Build: debug androidTest APK: SDK library debug variant, debuggable, no R8/minify (ADR-0014).
- Injection: target 1 events/s, actual 1.0 events/s over 59.0 s (60 of 60 calls); max calls in flight 1; start lateness {'p95': 4.9, 'max': 6.6}; event size 1024 bytes.
- track() completion (ms): p50 32.22, p95 48.15, max 82.38 (n=60).
- Errors: track() not ok 0; SDK WARN/ERROR lines 0; last delivery error None.
- Reconciliation with server DB: tracked ok 60, server rows 60, missing 0, not tracked 0, duplicate rows 0; sent on the wire 60 events in 11 batches, IDs sent more than once 0, batches not 200: 0, ACK statuses {'accepted': 60}.
- Queue drain: {'seconds': 2.19, 'queued_after': 0, 'queued_at_injection_end': 5}.
- Files (bytes): {'at_injection_end': {'installation-id': 36, 'queue.db': 45056, 'queue.db-shm': 32768, 'queue.db-wal': 432632}, 'after_drain': {'installation-id': 36, 'queue.db': 45056, 'queue.db-shm': 32768, 'queue.db-wal': 432632}}.
- Memory (KiB): {'pss_after_init_to_injection_end': 6940, 'pss_after_init_to_after_drain': 3412, 'heap_after_init_to_after_drain': 260, 'pss_absolute': {'before_init': 36653, 'after_init': 35525, 'injection_end': 42465, 'after_drain': 38937}}.
- Host: Apple M2 Pro, 12 CPUs, load average before { 12.41 7.21 5.55 }, after { 19.96 10.47 6.89 }. Run 2026-10-24T13:43:17Z to 2026-10-24T13:44:20Z, git 049f7ed.
