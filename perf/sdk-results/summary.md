# SDK device measurement summary (issue 24)

Measured in this environment (emulators, Compose server on one Mac). Not an SLA. A run that missed the target injection rate is `invalid`, never `pass` (spec section 7). One run per AVD and scenario. Method and criteria: ADR-0014.

| AVD | Scenario | Verdict | Target / actual events/s | Max in flight | track() p50 / p95 / max ms | Not ok | Missing / dup rows | Drain s | queue.db + wal + shm bytes (after drain) | PSS delta KiB |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| signaldock-api24 | burst | pass | 100 / 100.0 | 3 | 1.18 / 2.46 / 26.17 | 0 | 0 / 0 | 0.06 | 45056 + wal 432632 + shm 32768 | 7565 |
| signaldock-api24 | normal | pass | 1 / 1.0 | 1 | 2.36 / 5.73 / 8.3 | 0 | 0 / 0 | 0.94 | 36864 + wal 420272 + shm 32768 | 6599 |
| signaldock-api24 | offline | pass | back to back / 1075.4 | 1 | 0.78 / 1.89 / 12.89 | 0 | 0 / 0 | 16.24 | 335872 + wal 469712 + shm 32768 | 7982 |
| signaldock-api37 | burst | pass | 100 / 99.98 | 4 | 1.31 / 2.72 / 47.01 | 0 | 0 / 0 | 0.06 | 36864 + wal 449112 + shm 32768 | 2700 |
| signaldock-api37 | normal | pass | 1 / 1.0 | 1 | 1.85 / 5.37 / 18.95 | 0 | 0 / 0 | 2.1 | 36864 + wal 424392 + shm 32768 | 2218 |
| signaldock-api37 | offline | pass | back to back / 943.49 | 1 | 0.77 / 2.86 / 20.03 | 0 | 0 / 0 | 16.65 | 262144 + wal 510912 + shm 32768 | 3467 |

Offline row: track() numbers are the offline store phase (endpoint unreachable, calls back to back); drain seconds are the restarted process after the persisted retry wait (see the run details).

### signaldock-api24 (API 24) - burst: **pass**

- Device: Google Android SDK built for arm64, Android 7.0 (API 24), arm64-v8a. Build: debug androidTest APK: SDK library debug variant, debuggable, no R8/minify (ADR-0014).
- Injection: target 100 events/s, actual 100.0 events/s over 9.99 s (1000 of 1000 calls); max calls in flight 3; start lateness {'p95': 3.3, 'max': 4.3}; event size 1022 bytes.
- track() completion (ms): p50 1.18, p95 2.46, max 26.17 (n=1000).
- Errors: track() not ok 0; SDK WARN/ERROR lines 0; last delivery error None.
- Reconciliation with server DB: tracked ok 1000, server rows 1000, missing 0, not tracked 0, duplicate rows 0; sent on the wire 1000 events in 364 batches, IDs sent more than once 0, batches not 200: 0, ACK statuses {'accepted': 1000}.
- Queue drain: {'seconds': 0.06, 'queued_after': 0, 'queued_at_injection_end': 19}.
- Files (bytes): {'at_injection_end': {'installation-id': 36, 'queue.db': 45056, 'queue.db-shm': 32768, 'queue.db-wal': 432632}, 'after_drain': {'installation-id': 36, 'queue.db': 45056, 'queue.db-shm': 32768, 'queue.db-wal': 432632}}.
- Memory (KiB): {'pss_after_init_to_injection_end': 7549, 'pss_after_init_to_after_drain': 7565, 'heap_after_init_to_after_drain': 3501, 'pss_absolute': {'before_init': 19406, 'after_init': 22901, 'injection_end': 30450, 'after_drain': 30466}}.
- Host: Apple M2 Pro, 12 CPUs, load average before { 10.43 9.02 7.71 }, after { 9.46 8.85 7.67 }. Run 2026-10-12T16:10:23Z to 2026-10-12T16:10:34Z, git 5123400 (dirty).

### signaldock-api24 (API 24) - normal: **pass**

- Device: Google Android SDK built for arm64, Android 7.0 (API 24), arm64-v8a. Build: debug androidTest APK: SDK library debug variant, debuggable, no R8/minify (ADR-0014).
- Injection: target 1 events/s, actual 1.0 events/s over 59.01 s (60 of 60 calls); max calls in flight 1; start lateness {'p95': 10.3, 'max': 11.0}; event size 1024 bytes.
- track() completion (ms): p50 2.36, p95 5.73, max 8.3 (n=60).
- Errors: track() not ok 0; SDK WARN/ERROR lines 0; last delivery error None.
- Reconciliation with server DB: tracked ok 60, server rows 60, missing 0, not tracked 0, duplicate rows 0; sent on the wire 60 events in 12 batches, IDs sent more than once 0, batches not 200: 0, ACK statuses {'accepted': 60}.
- Queue drain: {'seconds': 0.94, 'queued_after': 0, 'queued_at_injection_end': 6}.
- Files (bytes): {'at_injection_end': {'installation-id': 36, 'queue.db': 36864, 'queue.db-shm': 32768, 'queue.db-wal': 420272}, 'after_drain': {'installation-id': 36, 'queue.db': 36864, 'queue.db-shm': 32768, 'queue.db-wal': 420272}}.
- Memory (KiB): {'pss_after_init_to_injection_end': 6075, 'pss_after_init_to_after_drain': 6599, 'heap_after_init_to_after_drain': 4440, 'pss_absolute': {'before_init': 18537, 'after_init': 22373, 'injection_end': 28448, 'after_drain': 28972}}.
- Host: Apple M2 Pro, 12 CPUs, load average before { 14.30 8.14 7.25 }, after { 13.18 9.40 7.81 }. Run 2026-10-12T16:08:59Z to 2026-10-12T16:10:00Z, git 5123400 (dirty).

### signaldock-api24 (API 24) - offline: **pass**

- Device: Google Android SDK built for arm64, Android 7.0 (API 24), arm64-v8a. Build: debug androidTest APK: SDK library debug variant, debuggable, no R8/minify (ADR-0014).
- Injection: target back to back events/s, actual 1075.4 events/s over 0.93 s (1000 of 1000 calls); max calls in flight 1; start lateness None; event size 1026 bytes.
- track() completion (ms): p50 0.78, p95 1.89, max 12.89 (n=1000).
- Errors: track() not ok 0; SDK WARN/ERROR lines 0; last delivery error NETWORK.
- Reconciliation with server DB: tracked ok 1000, server rows 1000, missing 0, not tracked 0, duplicate rows 0; sent on the wire 1000 events in 20 batches, IDs sent more than once 0, batches not 200: 0, ACK statuses {'accepted': 1000}.
- Queue drain: {'seconds': 16.24, 'queued_after': 0, 'queued_at_injection_end': 1000, 'persisted_retry_wait_at_start_s': 4.3, 'seconds_after_retry_wait': 11.97}.
- Files (bytes): {'after_store': {'installation-id': 36, 'queue.db': 1425408, 'queue.db-shm': 32768, 'queue.db-wal': 444992}, 'after_drain': {'installation-id': 36, 'queue.db': 335872, 'queue.db-shm': 32768, 'queue.db-wal': 469712}}.
- Memory (KiB): {'store_process': {'pss_after_init_to_after_store': 7982, 'heap_after_init_to_after_store': 2658}, 'drain_process': {'pss_before_init_to_after_drain': 8992, 'heap_before_init_to_after_drain': -489}, 'pss_absolute': {'store_after_store': 30937, 'drain_after_drain': 27830}}.
- Host: Apple M2 Pro, 12 CPUs, load average before { 13.18 9.40 7.81 }, after { 10.43 9.02 7.71 }. Run 2026-10-12T16:10:01Z to 2026-10-12T16:10:22Z, git 5123400 (dirty).

### signaldock-api37 (API 37) - burst: **pass**

- Device: Google sdk_gphone16k_arm64, Android 17 (API 37), arm64-v8a. Build: debug androidTest APK: SDK library debug variant, debuggable, no R8/minify (ADR-0014).
- Injection: target 100 events/s, actual 99.98 events/s over 9.99 s (1000 of 1000 calls); max calls in flight 4; start lateness {'p95': 2.8, 'max': 10.3}; event size 1022 bytes.
- track() completion (ms): p50 1.31, p95 2.72, max 47.01 (n=1000).
- Errors: track() not ok 0; SDK WARN/ERROR lines 0; last delivery error None.
- Reconciliation with server DB: tracked ok 1000, server rows 1000, missing 0, not tracked 0, duplicate rows 0; sent on the wire 1000 events in 420 batches, IDs sent more than once 0, batches not 200: 0, ACK statuses {'accepted': 1000}.
- Queue drain: {'seconds': 0.06, 'queued_after': 0, 'queued_at_injection_end': 7}.
- Files (bytes): {'at_injection_end': {'installation-id': 36, 'queue.db': 36864, 'queue.db-shm': 32768, 'queue.db-wal': 449112}, 'after_drain': {'installation-id': 36, 'queue.db': 36864, 'queue.db-shm': 32768, 'queue.db-wal': 449112}}.
- Memory (KiB): {'pss_after_init_to_injection_end': 3708, 'pss_after_init_to_after_drain': 2700, 'heap_after_init_to_after_drain': -48, 'pss_absolute': {'before_init': 39407, 'after_init': 38856, 'injection_end': 42564, 'after_drain': 41556}}.
- Host: Apple M2 Pro, 12 CPUs, load average before { 10.56 6.83 6.77 }, after { 14.22 7.82 7.12 }. Run 2026-10-12T16:08:30Z to 2026-10-12T16:08:41Z, git 5123400 (dirty).

### signaldock-api37 (API 37) - normal: **pass**

- Device: Google sdk_gphone16k_arm64, Android 17 (API 37), arm64-v8a. Build: debug androidTest APK: SDK library debug variant, debuggable, no R8/minify (ADR-0014).
- Injection: target 1 events/s, actual 1.0 events/s over 59.0 s (60 of 60 calls); max calls in flight 1; start lateness {'p95': 3.6, 'max': 4.3}; event size 1024 bytes.
- track() completion (ms): p50 1.85, p95 5.37, max 18.95 (n=60).
- Errors: track() not ok 0; SDK WARN/ERROR lines 0; last delivery error None.
- Reconciliation with server DB: tracked ok 60, server rows 60, missing 0, not tracked 0, duplicate rows 0; sent on the wire 60 events in 12 batches, IDs sent more than once 0, batches not 200: 0, ACK statuses {'accepted': 60}.
- Queue drain: {'seconds': 2.1, 'queued_after': 0, 'queued_at_injection_end': 7}.
- Files (bytes): {'at_injection_end': {'installation-id': 36, 'queue.db': 36864, 'queue.db-shm': 32768, 'queue.db-wal': 424392}, 'after_drain': {'installation-id': 36, 'queue.db': 36864, 'queue.db-shm': 32768, 'queue.db-wal': 424392}}.
- Memory (KiB): {'pss_after_init_to_injection_end': 2746, 'pss_after_init_to_after_drain': 2218, 'heap_after_init_to_after_drain': -224, 'pss_absolute': {'before_init': 38296, 'after_init': 38581, 'injection_end': 41327, 'after_drain': 40799}}.
- Host: Apple M2 Pro, 12 CPUs, load average before { 5.29 5.58 6.40 }, after { 6.91 5.93 6.46 }. Run 2026-10-12T16:07:03Z to 2026-10-12T16:08:06Z, git 5123400 (dirty).

### signaldock-api37 (API 37) - offline: **pass**

- Device: Google sdk_gphone16k_arm64, Android 17 (API 37), arm64-v8a. Build: debug androidTest APK: SDK library debug variant, debuggable, no R8/minify (ADR-0014).
- Injection: target back to back events/s, actual 943.49 events/s over 1.06 s (1000 of 1000 calls); max calls in flight 1; start lateness None; event size 1026 bytes.
- track() completion (ms): p50 0.77, p95 2.86, max 20.03 (n=1000).
- Errors: track() not ok 0; SDK WARN/ERROR lines 0; last delivery error NETWORK.
- Reconciliation with server DB: tracked ok 1000, server rows 1000, missing 0, not tracked 0, duplicate rows 0; sent on the wire 1000 events in 20 batches, IDs sent more than once 0, batches not 200: 0, ACK statuses {'accepted': 1000}.
- Queue drain: {'seconds': 16.65, 'queued_after': 0, 'queued_at_injection_end': 1000, 'persisted_retry_wait_at_start_s': 2.2, 'seconds_after_retry_wait': 14.5}.
- Files (bytes): {'after_store': {'installation-id': 36, 'queue.db': 1425408, 'queue.db-shm': 32768, 'queue.db-wal': 444992}, 'after_drain': {'installation-id': 36, 'queue.db': 262144, 'queue.db-shm': 32768, 'queue.db-wal': 510912}}.
- Memory (KiB): {'store_process': {'pss_after_init_to_after_store': 3467, 'heap_after_init_to_after_store': -192}, 'drain_process': {'pss_before_init_to_after_drain': 1138, 'heap_before_init_to_after_drain': 448}, 'pss_absolute': {'store_after_store': 42184, 'drain_after_drain': 40505}}.
- Host: Apple M2 Pro, 12 CPUs, load average before { 6.91 5.93 6.46 }, after { 10.56 6.83 6.77 }. Run 2026-10-12T16:08:07Z to 2026-10-12T16:08:29Z, git 5123400 (dirty).
