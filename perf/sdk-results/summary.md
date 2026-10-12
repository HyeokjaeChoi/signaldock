# SDK device measurement summary (issue 24)

Measured in this environment (emulators, Compose server on one Mac). Not an SLA. A run that missed the target injection rate is `invalid`, never `pass` (spec section 7). One run per AVD and scenario. Method and criteria: ADR-0014.

| AVD | Scenario | Verdict | Target / actual events/s | Max in flight | track() p50 / p95 / max ms | Not ok | Missing / dup rows | Drain s | queue.db + wal + shm bytes (after drain) | PSS delta KiB |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| signaldock-api24 | burst | pass | 100 / 100.01 | 3 | 0.96 / 2.06 / 24.21 | 0 | 0 / 0 | 0.07 | 57344 + wal 436752 + shm 32768 | 7462 |
| signaldock-api24 | normal | pass | 1 / 1.0 | 1 | 1.83 / 3.52 / 6.22 | 0 | 0 / 0 | 0.99 | 36864 + wal 420272 + shm 32768 | 6335 |
| signaldock-api24 | offline | pass | back to back / 1037.72 | 1 | 0.82 / 1.92 / 9.29 | 0 | 0 / 0 | 16.33 | 331776 + wal 524288 + shm 32768 | 8424 |
| signaldock-api37 | burst | pass | 100 / 100.0 | 3 | 0.84 / 2.04 / 28.65 | 0 | 0 / 0 | 0.06 | 45056 + wal 449112 + shm 32768 | 2506 |
| signaldock-api37 | normal | pass | 1 / 1.0 | 1 | 2.34 / 7.09 / 30.93 | 0 | 0 / 0 | 1.51 | 36864 + wal 424392 + shm 32768 | 2206 |
| signaldock-api37 | offline | pass | back to back / 928.72 | 1 | 0.81 / 2.79 / 16.45 | 0 | 0 / 0 | 16.55 | 622592 + wal 457352 + shm 32768 | 3548 |

Offline row: track() numbers are the offline store phase (endpoint unreachable, calls back to back); drain seconds are the restarted process after the persisted retry wait (see the run details).

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

### signaldock-api24 (API 24) - normal: **pass**

- Device: Google Android SDK built for arm64, Android 7.0 (API 24), arm64-v8a. Build: debug androidTest APK: SDK library debug variant, debuggable, no R8/minify (ADR-0014).
- Injection: target 1 events/s, actual 1.0 events/s over 59.0 s (60 of 60 calls); max calls in flight 1; start lateness {'p95': 9.4, 'max': 10.4}; event size 1025 bytes.
- track() completion (ms): p50 1.83, p95 3.52, max 6.22 (n=60).
- Errors: track() not ok 0; SDK WARN/ERROR lines 0; last delivery error None.
- Reconciliation with server DB: tracked ok 60, server rows 60, missing 0, not tracked 0, duplicate rows 0; sent on the wire 60 events in 9 batches, IDs sent more than once 0, batches not 200: 0, ACK statuses {'accepted': 60}.
- Queue drain: {'seconds': 0.99, 'queued_after': 0, 'queued_at_injection_end': 7}.
- Files (bytes): {'at_injection_end': {'installation-id': 36, 'queue.db': 36864, 'queue.db-shm': 32768, 'queue.db-wal': 420272}, 'after_drain': {'installation-id': 36, 'queue.db': 36864, 'queue.db-shm': 32768, 'queue.db-wal': 420272}}.
- Memory (KiB): {'pss_after_init_to_injection_end': 5727, 'pss_after_init_to_after_drain': 6335, 'heap_after_init_to_after_drain': 4273, 'pss_absolute': {'before_init': 18533, 'after_init': 22318, 'injection_end': 28045, 'after_drain': 28653}}.
- Host: Apple M2 Pro, 12 CPUs, load average before { 8.56 7.56 7.41 }, after { 6.45 7.10 7.24 }. Run 2026-10-12T15:58:12Z to 2026-10-12T15:59:13Z, git 6bd96af (dirty).

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

### signaldock-api37 (API 37) - burst: **pass**

- Device: Google sdk_gphone16k_arm64, Android 17 (API 37), arm64-v8a. Build: debug androidTest APK: SDK library debug variant, debuggable, no R8/minify (ADR-0014).
- Injection: target 100 events/s, actual 100.0 events/s over 9.99 s (1000 of 1000 calls); max calls in flight 3; start lateness {'p95': 2.5, 'max': 9.5}; event size 1024 bytes.
- track() completion (ms): p50 0.84, p95 2.04, max 28.65 (n=1000).
- Errors: track() not ok 0; SDK WARN/ERROR lines 0; last delivery error None.
- Reconciliation with server DB: tracked ok 1000, server rows 1000, missing 0, not tracked 0, duplicate rows 0; sent on the wire 1000 events in 202 batches, IDs sent more than once 0, batches not 200: 0, ACK statuses {'accepted': 1000}.
- Queue drain: {'seconds': 0.06, 'queued_after': 0, 'queued_at_injection_end': 18}.
- Files (bytes): {'at_injection_end': {'installation-id': 36, 'queue.db': 45056, 'queue.db-shm': 32768, 'queue.db-wal': 449112}, 'after_drain': {'installation-id': 36, 'queue.db': 45056, 'queue.db-shm': 32768, 'queue.db-wal': 449112}}.
- Memory (KiB): {'pss_after_init_to_injection_end': 3770, 'pss_after_init_to_after_drain': 2506, 'heap_after_init_to_after_drain': 0, 'pss_absolute': {'before_init': 39267, 'after_init': 38749, 'injection_end': 42519, 'after_drain': 41255}}.
- Host: Apple M2 Pro, 12 CPUs, load average before { 8.01 7.33 7.33 }, after { 8.32 7.42 7.36 }. Run 2026-10-12T15:57:22Z to 2026-10-12T15:57:34Z, git 6bd96af (dirty).

### signaldock-api37 (API 37) - normal: **pass**

- Device: Google sdk_gphone16k_arm64, Android 17 (API 37), arm64-v8a. Build: debug androidTest APK: SDK library debug variant, debuggable, no R8/minify (ADR-0014).
- Injection: target 1 events/s, actual 1.0 events/s over 59.0 s (60 of 60 calls); max calls in flight 1; start lateness {'p95': 7.6, 'max': 11.2}; event size 1025 bytes.
- track() completion (ms): p50 2.34, p95 7.09, max 30.93 (n=60).
- Errors: track() not ok 0; SDK WARN/ERROR lines 0; last delivery error None.
- Reconciliation with server DB: tracked ok 60, server rows 60, missing 0, not tracked 0, duplicate rows 0; sent on the wire 60 events in 11 batches, IDs sent more than once 0, batches not 200: 0, ACK statuses {'accepted': 60}.
- Queue drain: {'seconds': 1.51, 'queued_after': 0, 'queued_at_injection_end': 7}.
- Files (bytes): {'at_injection_end': {'installation-id': 36, 'queue.db': 36864, 'queue.db-shm': 32768, 'queue.db-wal': 424392}, 'after_drain': {'installation-id': 36, 'queue.db': 36864, 'queue.db-shm': 32768, 'queue.db-wal': 424392}}.
- Memory (KiB): {'pss_after_init_to_injection_end': 3310, 'pss_after_init_to_after_drain': 2206, 'heap_after_init_to_after_drain': -48, 'pss_absolute': {'before_init': 39220, 'after_init': 38637, 'injection_end': 41947, 'after_drain': 40843}}.
- Host: Apple M2 Pro, 12 CPUs, load average before { 7.61 7.17 7.28 }, after { 6.98 7.08 7.24 }. Run 2026-10-12T15:55:56Z to 2026-10-12T15:56:58Z, git 6bd96af (dirty).

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
