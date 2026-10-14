### signaldock-api37 (API 37) - offline, release build: **pass**

- Device: Google sdk_gphone16k_arm64, Android 17 (API 37), arm64-v8a. Build: release measure app (sdk/measure-app): SDK as dependency, R8 minify and shrinkResources on, debug-signed release variant, not debuggable (ADR-0018).
- Injection: target back to back events/s, actual 2082.08 events/s over 0.48 s (1000 of 1000 calls); max calls in flight 1; start lateness None; event size 1026 bytes.
- track() completion (ms): p50 0.32, p95 1.21, max 5.78 (n=1000).
- Errors: track() not ok 0; SDK WARN/ERROR lines 0; last delivery error NETWORK.
- Reconciliation with server DB: tracked ok 1000, server rows 1000, missing 0, not tracked 0, duplicate rows 0; sent on the wire 1000 events in 20 batches, IDs sent more than once 0, batches not 200: 0, ACK statuses {'accepted': 1000}.
- Queue drain: {'seconds': 17.92, 'queued_after': 0, 'queued_at_injection_end': 1000, 'persisted_retry_wait_at_start_s': 16.2, 'seconds_after_retry_wait': 1.74}.
- Files (bytes): {'after_store': {'installation-id': 36, 'queue.db': 1433600, 'queue.db-shm': 32768, 'queue.db-wal': 444992}, 'after_drain': {'installation-id': 36, 'queue.db': 622592, 'queue.db-shm': 32768, 'queue.db-wal': 506792}}.
- Memory (KiB): {'store_process': {'pss_after_init_to_after_store': 2879, 'heap_after_init_to_after_store': -256}, 'drain_process': {'pss_before_init_to_after_drain': 3191, 'heap_before_init_to_after_drain': 176}, 'pss_absolute': {'store_after_store': 14273, 'drain_after_drain': 13095}}.
- Host: Apple M2 Pro, 12 CPUs, load average before { 7.08 10.90 9.10 }, after { 6.50 10.52 9.00 }. Run 2026-10-14T00:47:02Z to 2026-10-14T00:47:25Z, git 1f80d7d (dirty).
