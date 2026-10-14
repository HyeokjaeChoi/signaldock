### signaldock-api37 (API 37) - burst, release build: **pass**

- Device: Google sdk_gphone16k_arm64, Android 17 (API 37), arm64-v8a. Build: release measure app (sdk/measure-app): SDK as dependency, R8 minify and shrinkResources on, debug-signed release variant, not debuggable (ADR-0018).
- Injection: target 100 events/s, actual 99.99 events/s over 9.99 s (1000 of 1000 calls); max calls in flight 1; start lateness {'p95': 2.9, 'max': 4.3}; event size 1022 bytes.
- track() completion (ms): p50 0.41, p95 0.95, max 4.86 (n=1000).
- Errors: track() not ok 0; SDK WARN/ERROR lines 0; last delivery error None.
- Reconciliation with server DB: tracked ok 1000, server rows 1000, missing 0, not tracked 0, duplicate rows 0; sent on the wire 1000 events in 154 batches, IDs sent more than once 0, batches not 200: 0, ACK statuses {'accepted': 1000}.
- Queue drain: {'seconds': 0.07, 'queued_after': 0, 'queued_at_injection_end': 18}.
- Files (bytes): {'at_injection_end': {'installation-id': 36, 'queue.db': 36864, 'queue.db-shm': 32768, 'queue.db-wal': 440872}, 'after_drain': {'installation-id': 36, 'queue.db': 36864, 'queue.db-shm': 32768, 'queue.db-wal': 440872}}.
- Memory (KiB): {'pss_after_init_to_injection_end': 4185, 'pss_after_init_to_after_drain': 3369, 'heap_after_init_to_after_drain': -80, 'pss_absolute': {'before_init': 9745, 'after_init': 11456, 'injection_end': 15641, 'after_drain': 14825}}.
- Host: Apple M2 Pro, 12 CPUs, load average before { 6.14 10.38 8.96 }, after { 5.82 10.17 8.90 }. Run 2026-10-14T00:47:25Z to 2026-10-14T00:47:37Z, git 1f80d7d (dirty).
