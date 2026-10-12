### signaldock-api24 (API 24) - burst: **pass**

- Device: Google Android SDK built for arm64, Android 7.0 (API 24), arm64-v8a. Build: debug androidTest APK: SDK library debug variant, debuggable, no R8/minify (ADR-0014).
- Injection: target 100 events/s, actual 99.97 events/s over 9.99 s (1000 of 1000 calls); max calls in flight 1; start lateness {'p95': 3.6, 'max': 4.5}; event size 1022 bytes.
- track() completion (ms): p50 1.15, p95 2.25, max 7.0 (n=1000).
- Errors: track() not ok 0; SDK WARN/ERROR lines 0; last delivery error None.
- Reconciliation with server DB: tracked ok 1000, server rows 1000, missing 0, not tracked 0, duplicate rows 0; sent on the wire 1000 events in 265 batches, IDs sent more than once 0, batches not 200: 0, ACK statuses {'accepted': 1000}.
- Queue drain: {'seconds': 0.06, 'queued_after': 0, 'queued_at_injection_end': 18}.
- Files (bytes): {'at_injection_end': {'installation-id': 36, 'queue.db': 57344, 'queue.db-shm': 32768, 'queue.db-wal': 436752}, 'after_drain': {'installation-id': 36, 'queue.db': 57344, 'queue.db-shm': 32768, 'queue.db-wal': 436752}}.
- Memory (KiB): {'pss_after_init_to_injection_end': 7929, 'pss_after_init_to_after_drain': 6797, 'heap_after_init_to_after_drain': 2939, 'pss_absolute': {'before_init': 19405, 'after_init': 23154, 'injection_end': 31083, 'after_drain': 29951}}.
- Host: Apple M2 Pro, 12 CPUs, load average before { 4.62 7.35 7.83 }, after { 4.52 7.24 7.79 }. Run 2026-10-12T16:18:46Z to 2026-10-12T16:18:58Z, git 5b369f4 (dirty).
