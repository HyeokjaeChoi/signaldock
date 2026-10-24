### SM-F766N (physical) (API 37) - offline, debug build: **pass**

- Device: samsung SM-F766N, Android 17 (API 37), arm64-v8a. Build: debug androidTest APK: SDK library debug variant, debuggable, no R8/minify (ADR-0014).
- Injection: target back to back events/s, actual 96.1 events/s over 10.4 s (1000 of 1000 calls); max calls in flight 1; start lateness None; event size 1026 bytes.
- track() completion (ms): p50 9.64, p95 16.48, max 74.45 (n=1000).
- Errors: track() not ok 0; SDK WARN/ERROR lines 0; last delivery error NETWORK.
- Reconciliation with server DB: tracked ok 1000, server rows 1000, missing 0, not tracked 0, duplicate rows 0; sent on the wire 1000 events in 20 batches, IDs sent more than once 0, batches not 200: 0, ACK statuses {'accepted': 1000}.
- Queue drain: {'seconds': 69.72, 'queued_after': 0, 'queued_at_injection_end': 1000, 'persisted_retry_wait_at_start_s': 30.8, 'seconds_after_retry_wait': 38.96}.
- Files (bytes): {'after_store': {'installation-id': 36, 'queue.db': 1433600, 'queue.db-shm': 32768, 'queue.db-wal': 432632}, 'after_drain': {'installation-id': 36, 'queue.db': 266240, 'queue.db-shm': 32768, 'queue.db-wal': 486192}}.
- Memory (KiB): {'store_process': {'pss_after_init_to_after_store': 4383, 'heap_after_init_to_after_store': -28}, 'drain_process': {'pss_before_init_to_after_drain': 1356, 'heap_before_init_to_after_drain': 588}, 'pss_absolute': {'store_after_store': 40056, 'drain_after_drain': 38207}}.
- Host: Apple M2 Pro, 12 CPUs, load average before { 19.96 10.47 6.89 }, after { 11.12 10.43 7.26 }. Run 2026-10-24T13:44:22Z to 2026-10-24T13:45:48Z, git 049f7ed.
