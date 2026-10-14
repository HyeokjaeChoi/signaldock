### signaldock-api37 (API 37) - offline, release build: **pass**

- Device: Google sdk_gphone16k_arm64, Android 17 (API 37), arm64-v8a. Build: release measure app (sdk/measure-app): SDK as dependency, R8 minify and shrinkResources on, debug-signed release variant, not debuggable (ADR-0018).
- Injection: target back to back events/s, actual 2103.4 events/s over 0.47 s (1000 of 1000 calls); max calls in flight 1; start lateness None; event size 1026 bytes.
- track() completion (ms): p50 0.33, p95 1.16, max 7.37 (n=1000).
- Errors: track() not ok 0; SDK WARN/ERROR lines 0; last delivery error NETWORK.
- Reconciliation with server DB: tracked ok 1000, server rows 1000, missing 0, not tracked 0, duplicate rows 0; sent on the wire 1000 events in 20 batches, IDs sent more than once 0, batches not 200: 0, ACK statuses {'accepted': 1000}.
- Queue drain: {'seconds': 17.2, 'queued_after': 0, 'queued_at_injection_end': 1000, 'persisted_retry_wait_at_start_s': 0, 'seconds_after_retry_wait': 17.2}.
- Files (bytes): {'after_store': {'installation-id': 36, 'queue.db': 1441792, 'queue.db-shm': 32768, 'queue.db-wal': 444992}, 'after_drain': {'installation-id': 36, 'queue.db': 622592, 'queue.db-shm': 32768, 'queue.db-wal': 444992}}.
- Memory (KiB): {'store_process': {'pss_after_init_to_after_store': 2667, 'heap_after_init_to_after_store': -352}, 'drain_process': {'pss_before_init_to_after_drain': 3620, 'heap_before_init_to_after_drain': 96}, 'pss_absolute': {'store_after_store': 14181, 'drain_after_drain': 13513}}.
- Host: Apple M2 Pro, 12 CPUs, load average before { 8.05 7.07 6.10 }, after { 7.53 7.04 6.12 }. Run 2026-10-14T00:35:39Z to 2026-10-14T00:36:01Z, git 79ee88f (dirty).
