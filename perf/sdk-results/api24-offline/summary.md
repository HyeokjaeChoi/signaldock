### signaldock-api24 (API 24) - offline: **pass**

- Device: Google Android SDK built for arm64, Android 7.0 (API 24), arm64-v8a. Build: debug androidTest APK: SDK library debug variant, debuggable, no R8/minify (ADR-0014).
- Injection: target back to back events/s, actual 1127.8 events/s over 0.89 s (1000 of 1000 calls); max calls in flight 1; start lateness None; event size 1026 bytes.
- track() completion (ms): p50 0.76, p95 1.66, max 6.08 (n=1000).
- Errors: track() not ok 0; SDK WARN/ERROR lines 0; last delivery error NETWORK.
- Reconciliation with server DB: tracked ok 1000, server rows 1000, missing 0, not tracked 0, duplicate rows 0; sent on the wire 1000 events in 20 batches, IDs sent more than once 0, batches not 200: 0, ACK statuses {'accepted': 1000}.
- Queue drain: {'seconds': 16.29, 'queued_after': 0, 'queued_at_injection_end': 1000, 'persisted_retry_wait_at_start_s': 5.4, 'seconds_after_retry_wait': 10.86}.
- Files (bytes): {'after_store': {'installation-id': 36, 'queue.db': 1437696, 'queue.db-shm': 32768, 'queue.db-wal': 432632}, 'after_drain': {'installation-id': 36, 'queue.db': 626688, 'queue.db-shm': 32768, 'queue.db-wal': 465592}}.
- Memory (KiB): {'store_process': {'pss_after_init_to_after_store': 8156, 'heap_after_init_to_after_store': 2700}, 'drain_process': {'pss_before_init_to_after_drain': 10302, 'heap_before_init_to_after_drain': 748}, 'pss_absolute': {'store_after_store': 30982, 'drain_after_drain': 29125}}.
- Host: Apple M2 Pro, 12 CPUs, load average before { 4.47 7.56 7.92 }, after { 4.62 7.35 7.83 }. Run 2026-10-12T16:18:25Z to 2026-10-12T16:18:46Z, git 5b369f4 (dirty).
