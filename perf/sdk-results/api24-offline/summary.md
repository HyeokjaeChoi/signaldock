### signaldock-api24 (API 24) - offline: **pass**

- Device: Google Android SDK built for arm64, Android 7.0 (API 24), arm64-v8a. Build: debug androidTest APK: SDK library debug variant, debuggable, no R8/minify (ADR-0014).
- Injection: target back to back events/s, actual 1075.4 events/s over 0.93 s (1000 of 1000 calls); max calls in flight 1; start lateness None; event size 1026 bytes.
- track() completion (ms): p50 0.78, p95 1.89, max 12.89 (n=1000).
- Errors: track() not ok 0; SDK WARN/ERROR lines 0; last delivery error NETWORK.
- Reconciliation with server DB: tracked ok 1000, server rows 1000, missing 0, not tracked 0, duplicate rows 0; sent on the wire 1000 events in 20 batches, IDs sent more than once 0, batches not 200: 0, ACK statuses {'accepted': 1000}.
- Queue drain: {'seconds': 16.24, 'queued_after': 0, 'queued_at_injection_end': 1000, 'persisted_retry_wait_at_start_s': 4.3, 'seconds_after_retry_wait': 11.97}.
- Files (bytes): {'after_store': {'installation-id': 36, 'queue.db': 1425408, 'queue.db-shm': 32768, 'queue.db-wal': 444992}, 'after_drain': {'installation-id': 36, 'queue.db': 335872, 'queue.db-shm': 32768, 'queue.db-wal': 469712}}.
- Memory (KiB): {'store_process': {'pss_after_init_to_after_store': 7982, 'heap_after_init_to_after_store': 2658}, 'drain_process': {'pss_before_init_to_after_drain': 8992, 'heap_before_init_to_after_drain': -489}, 'pss_absolute': {'store_after_store': 30937, 'drain_after_drain': 27830}}.
- Host: Apple M2 Pro, 12 CPUs, load average before { 13.18 9.40 7.81 }, after { 10.43 9.02 7.71 }. Run 2026-10-12T16:10:01Z to 2026-10-12T16:10:22Z, git 5123400 (dirty).
