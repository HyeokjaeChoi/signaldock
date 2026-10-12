### signaldock-api24 (API 24) - offline: **pass**

- Device: Google Android SDK built for arm64, Android 7.0 (API 24), arm64-v8a. Build: debug androidTest APK: SDK library debug variant, debuggable, no R8/minify (ADR-0014).
- Injection: target back to back events/s, actual 1037.72 events/s over 0.96 s (1000 of 1000 calls); max calls in flight 1; start lateness None; event size 1026 bytes.
- track() completion (ms): p50 0.82, p95 1.92, max 9.29 (n=1000).
- Errors: track() not ok 0; SDK WARN/ERROR lines 0; last delivery error NETWORK.
- Reconciliation with server DB: tracked ok 1000, server rows 1000, missing 0, not tracked 0, duplicate rows 0; sent on the wire 1000 events in 20 batches, IDs sent more than once 0, batches not 200: 0, ACK statuses {'accepted': 1000}.
- Queue drain: {'seconds': 16.33, 'queued_after': 0, 'queued_at_injection_end': 1000, 'persisted_retry_wait_at_start_s': 3.9, 'seconds_after_retry_wait': 12.39}.
- Files (bytes): {'after_store': {'installation-id': 36, 'queue.db': 1425408, 'queue.db-shm': 32768, 'queue.db-wal': 432632}, 'after_drain': {'installation-id': 36, 'queue.db': 331776, 'queue.db-shm': 32768, 'queue.db-wal': 524288}}.
- Memory (KiB): {'store_process': {'pss_after_init_to_after_store': 8424, 'heap_after_init_to_after_store': 2941}, 'drain_process': {'pss_before_init_to_after_drain': 9005, 'heap_before_init_to_after_drain': -1204}, 'pss_absolute': {'store_after_store': 31504, 'drain_after_drain': 27861}}.
- Host: Apple M2 Pro, 12 CPUs, load average before { 6.45 7.10 7.24 }, after { 7.68 7.33 7.32 }. Run 2026-10-12T15:59:14Z to 2026-10-12T15:59:35Z, git 6bd96af (dirty).
