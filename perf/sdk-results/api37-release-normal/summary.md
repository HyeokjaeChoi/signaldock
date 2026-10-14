### signaldock-api37 (API 37) - normal, release build: **pass**

- Device: Google sdk_gphone16k_arm64, Android 17 (API 37), arm64-v8a. Build: release measure app (sdk/measure-app): SDK as dependency, R8 minify and shrinkResources on, debug-signed release variant, not debuggable (ADR-0018).
- Injection: target 1 events/s, actual 1.0 events/s over 59.0 s (60 of 60 calls); max calls in flight 1; start lateness {'p95': 8.4, 'max': 9.1}; event size 1024 bytes.
- track() completion (ms): p50 0.77, p95 0.95, max 4.13 (n=60).
- Errors: track() not ok 0; SDK WARN/ERROR lines 0; last delivery error None.
- Reconciliation with server DB: tracked ok 60, server rows 60, missing 0, not tracked 0, duplicate rows 0; sent on the wire 60 events in 11 batches, IDs sent more than once 0, batches not 200: 0, ACK statuses {'accepted': 60}.
- Queue drain: {'seconds': 1.47, 'queued_after': 0, 'queued_at_injection_end': 6}.
- Files (bytes): {'at_injection_end': {'installation-id': 36, 'queue.db': 36864, 'queue.db-shm': 32768, 'queue.db-wal': 424392}, 'after_drain': {'installation-id': 36, 'queue.db': 36864, 'queue.db-shm': 32768, 'queue.db-wal': 424392}}.
- Memory (KiB): {'pss_after_init_to_injection_end': 1966, 'pss_after_init_to_after_drain': 1470, 'heap_after_init_to_after_drain': -304, 'pss_absolute': {'before_init': 9709, 'after_init': 11362, 'injection_end': 13328, 'after_drain': 12832}}.
- Host: Apple M2 Pro, 12 CPUs, load average before { 13.34 12.56 9.50 }, after { 7.08 10.90 9.10 }. Run 2026-10-14T00:46:00Z to 2026-10-14T00:47:01Z, git 1f80d7d (dirty).
