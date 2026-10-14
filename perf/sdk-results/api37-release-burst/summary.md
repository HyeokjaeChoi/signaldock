### signaldock-api37 (API 37) - burst, release build: **pass**

- Device: Google sdk_gphone16k_arm64, Android 17 (API 37), arm64-v8a. Build: release measure app (sdk/measure-app): SDK as dependency, R8 minify and shrinkResources on, debug-signed release variant, not debuggable (ADR-0018).
- Injection: target 100 events/s, actual 99.98 events/s over 9.99 s (1000 of 1000 calls); max calls in flight 1; start lateness {'p95': 2.7, 'max': 3.8}; event size 1022 bytes.
- track() completion (ms): p50 0.48, p95 1.07, max 5.37 (n=1000).
- Errors: track() not ok 0; SDK WARN/ERROR lines 0; last delivery error None.
- Reconciliation with server DB: tracked ok 1000, server rows 1000, missing 0, not tracked 0, duplicate rows 0; sent on the wire 1000 events in 237 batches, IDs sent more than once 0, batches not 200: 0, ACK statuses {'accepted': 1000}.
- Queue drain: {'seconds': 0.06, 'queued_after': 0, 'queued_at_injection_end': 5}.
- Files (bytes): {'at_injection_end': {'installation-id': 36, 'queue.db': 36864, 'queue.db-shm': 32768, 'queue.db-wal': 436752}, 'after_drain': {'installation-id': 36, 'queue.db': 36864, 'queue.db-shm': 32768, 'queue.db-wal': 436752}}.
- Memory (KiB): {'pss_after_init_to_injection_end': 3996, 'pss_after_init_to_after_drain': 3436, 'heap_after_init_to_after_drain': -80, 'pss_absolute': {'before_init': 9877, 'after_init': 11380, 'injection_end': 15376, 'after_drain': 14816}}.
- Host: Apple M2 Pro, 12 CPUs, load average before { 7.53 7.04 6.12 }, after { 6.76 6.89 6.08 }. Run 2026-10-14T00:36:02Z to 2026-10-14T00:36:13Z, git 79ee88f (dirty).
