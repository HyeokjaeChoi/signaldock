### signaldock-api24 (API 24) - burst: **pass**

- Device: Google Android SDK built for arm64, Android 7.0 (API 24), arm64-v8a. Build: debug androidTest APK: SDK library debug variant, debuggable, no R8/minify (ADR-0014).
- Injection: target 100 events/s, actual 100.01 events/s over 9.99 s (1000 of 1000 calls); max calls in flight 3; start lateness {'p95': 1.7, 'max': 4.7}; event size 1024 bytes.
- track() completion (ms): p50 0.96, p95 2.06, max 24.21 (n=1000).
- Errors: track() not ok 0; SDK WARN/ERROR lines 0; last delivery error None.
- Reconciliation with server DB: tracked ok 1000, server rows 1000, missing 0, not tracked 0, duplicate rows 0; sent on the wire 1000 events in 181 batches, IDs sent more than once 0, batches not 200: 0, ACK statuses {'accepted': 1000}.
- Queue drain: {'seconds': 0.07, 'queued_after': 0, 'queued_at_injection_end': 18}.
- Files (bytes): {'at_injection_end': {'installation-id': 36, 'queue.db': 57344, 'queue.db-shm': 32768, 'queue.db-wal': 436752}, 'after_drain': {'installation-id': 36, 'queue.db': 57344, 'queue.db-shm': 32768, 'queue.db-wal': 436752}}.
- Memory (KiB): {'pss_after_init_to_injection_end': 7466, 'pss_after_init_to_after_drain': 7462, 'heap_after_init_to_after_drain': 3794, 'pss_absolute': {'before_init': 19381, 'after_init': 22772, 'injection_end': 30238, 'after_drain': 30234}}.
- Host: Apple M2 Pro, 12 CPUs, load average before { 7.68 7.33 7.32 }, after { 7.21 7.24 7.29 }. Run 2026-10-12T15:59:36Z to 2026-10-12T15:59:47Z, git 6bd96af (dirty).
