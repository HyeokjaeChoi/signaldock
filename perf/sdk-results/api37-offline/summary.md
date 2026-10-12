### signaldock-api37 (API 37) - offline: **pass**

- Device: Google sdk_gphone16k_arm64, Android 17 (API 37), arm64-v8a. Build: debug androidTest APK: SDK library debug variant, debuggable, no R8/minify (ADR-0014).
- Injection: target back to back events/s, actual 928.72 events/s over 1.08 s (1000 of 1000 calls); max calls in flight 1; start lateness None; event size 1026 bytes.
- track() completion (ms): p50 0.81, p95 2.79, max 16.45 (n=1000).
- Errors: track() not ok 0; SDK WARN/ERROR lines 0; last delivery error NETWORK.
- Reconciliation with server DB: tracked ok 1000, server rows 1000, missing 0, not tracked 0, duplicate rows 0; sent on the wire 1000 events in 20 batches, IDs sent more than once 0, batches not 200: 0, ACK statuses {'accepted': 1000}.
- Queue drain: {'seconds': 16.55, 'queued_after': 0, 'queued_at_injection_end': 1000, 'persisted_retry_wait_at_start_s': 3.6, 'seconds_after_retry_wait': 12.96}.
- Files (bytes): {'after_store': {'installation-id': 36, 'queue.db': 1437696, 'queue.db-shm': 32768, 'queue.db-wal': 432632}, 'after_drain': {'installation-id': 36, 'queue.db': 622592, 'queue.db-shm': 32768, 'queue.db-wal': 457352}}.
- Memory (KiB): {'store_process': {'pss_after_init_to_after_store': 3548, 'heap_after_init_to_after_store': -112}, 'drain_process': {'pss_before_init_to_after_drain': 1225, 'heap_before_init_to_after_drain': 448}, 'pss_absolute': {'store_after_store': 42317, 'drain_after_drain': 40522}}.
- Host: Apple M2 Pro, 12 CPUs, load average before { 6.98 7.08 7.24 }, after { 8.01 7.33 7.33 }. Run 2026-10-12T15:56:59Z to 2026-10-12T15:57:21Z, git 6bd96af (dirty).
