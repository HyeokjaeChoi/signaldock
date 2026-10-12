# SDK device measurement summary (issue 24)

Measured in this environment (emulators, Compose server on one Mac). Not an SLA. A run that missed the target injection rate is `invalid`, never `pass` (spec section 7). One run per AVD and scenario. Method and criteria: ADR-0014.

| AVD | Scenario | Verdict | Target / actual events/s | Max in flight | track() p50 / p95 / max ms | Not ok | Missing / dup rows | Drain s | queue.db + wal + shm bytes (after drain) | PSS delta KiB |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| signaldock-api24 | burst | pass | 100 / 99.97 | 1 | 1.15 / 2.25 / 7.0 | 0 | 0 / 0 | 0.06 | 57344 + wal 436752 + shm 32768 | 6797 |
| signaldock-api24 | normal | pass | 1 / 1.0 | 1 | 1.87 / 3.8 / 7.08 | 0 | 0 / 0 | 0.92 | 36864 + wal 420272 + shm 32768 | 7032 |
| signaldock-api24 | offline | pass | back to back / 1127.8 | 1 | 0.76 / 1.66 / 6.08 | 0 | 0 / 0 | 16.29 | 626688 + wal 465592 + shm 32768 | 8156 |
| signaldock-api37 | burst | pass | 100 / 99.99 | 4 | 0.85 / 2.29 / 52.14 | 0 | 0 / 0 | 0.06 | 36864 + wal 502672 + shm 32768 | 2702 |
| signaldock-api37 | normal | pass | 1 / 1.0 | 1 | 1.58 / 2.23 / 20.52 | 0 | 0 / 0 | 2.02 | 36864 + wal 424392 + shm 32768 | 2008 |
| signaldock-api37 | offline | pass | back to back / 1175.22 | 1 | 0.64 / 2.35 / 16.78 | 0 | 0 / 0 | 16.8 | 626688 + wal 473832 + shm 32768 | 3384 |

Offline row: track() numbers are the offline store phase (endpoint unreachable, calls back to back); drain seconds are the restarted process after the persisted retry wait (see the run details).

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

### signaldock-api24 (API 24) - normal: **pass**

- Device: Google Android SDK built for arm64, Android 7.0 (API 24), arm64-v8a. Build: debug androidTest APK: SDK library debug variant, debuggable, no R8/minify (ADR-0014).
- Injection: target 1 events/s, actual 1.0 events/s over 59.01 s (60 of 60 calls); max calls in flight 1; start lateness {'p95': 9.8, 'max': 10.3}; event size 1024 bytes.
- track() completion (ms): p50 1.87, p95 3.8, max 7.08 (n=60).
- Errors: track() not ok 0; SDK WARN/ERROR lines 0; last delivery error None.
- Reconciliation with server DB: tracked ok 60, server rows 60, missing 0, not tracked 0, duplicate rows 0; sent on the wire 60 events in 12 batches, IDs sent more than once 0, batches not 200: 0, ACK statuses {'accepted': 60}.
- Queue drain: {'seconds': 0.92, 'queued_after': 0, 'queued_at_injection_end': 7}.
- Files (bytes): {'at_injection_end': {'installation-id': 36, 'queue.db': 36864, 'queue.db-shm': 32768, 'queue.db-wal': 420272}, 'after_drain': {'installation-id': 36, 'queue.db': 36864, 'queue.db-shm': 32768, 'queue.db-wal': 420272}}.
- Memory (KiB): {'pss_after_init_to_injection_end': 6596, 'pss_after_init_to_after_drain': 7032, 'heap_after_init_to_after_drain': 4472, 'pss_absolute': {'before_init': 18515, 'after_init': 22401, 'injection_end': 28997, 'after_drain': 29433}}.
- Host: Apple M2 Pro, 12 CPUs, load average before { 5.30 8.34 8.20 }, after { 4.47 7.56 7.92 }. Run 2026-10-12T16:17:23Z to 2026-10-12T16:18:24Z, git 5b369f4 (dirty).

### signaldock-api24 (API 24) - offline: **pass**

- Device: Google Android SDK built for arm64, Android 7.0 (API 24), arm64-v8a. Build: debug androidTest APK: SDK library debug variant, debuggable, no R8/minify (ADR-0014).
- Injection: target back to back events/s, actual 1127.8 events/s over 0.89 s (1000 of 1000 calls); max calls in flight 1; start lateness None; event size 1026 bytes.
- track() completion (ms): p50 0.76, p95 1.66, max 6.08 (n=1000).
- Errors: track() not ok 0; SDK WARN/ERROR lines 0; last delivery error NETWORK.
- Reconciliation with server DB: tracked ok 1000, server rows 1000, missing 0, not tracked 0, duplicate rows 0; sent on the wire 1000 events in 20 batches, IDs sent more than once 0, batches not 200: 0, ACK statuses {'accepted': 1000}.
- Queue drain: {'seconds': 16.29, 'queued_after': 0, 'queued_at_injection_end': 1000, 'persisted_retry_wait_at_start_s': 5.4, 'seconds_after_retry_wait': 10.86}.
- Files (bytes): {'after_store': {'installation-id': 36, 'queue.db': 1437696, 'queue.db-shm': 32768, 'queue.db-wal': 432632}, 'after_drain': {'installation-id': 36, 'queue.db': 626688, 'queue.db-shm': 32768, 'queue.db-wal': 465592}}.
- Memory (KiB): {'store_process': {'pss_after_init_to_after_store': 8156, 'heap_after_init_to_after_store': 2700}, 'drain_process': {'pss_before_init_to_after_drain': 10302, 'heap_before_init_to_after_drain': 748}, 'pss_absolute': {'store_after_store': 30982, 'drain_after_drain': 29125}}.
- Host: Apple M2 Pro, 12 CPUs, load average before { 4.47 7.56 7.92 }, after { 4.62 7.35 7.83 }. Run 2026-10-12T16:18:25Z to 2026-10-12T16:18:46Z, git 5b369f4 (dirty).

### signaldock-api37 (API 37) - burst: **pass**

- Device: Google sdk_gphone16k_arm64, Android 17 (API 37), arm64-v8a. Build: debug androidTest APK: SDK library debug variant, debuggable, no R8/minify (ADR-0014).
- Injection: target 100 events/s, actual 99.99 events/s over 9.99 s (1000 of 1000 calls); max calls in flight 4; start lateness {'p95': 2.7, 'max': 6.4}; event size 1022 bytes.
- track() completion (ms): p50 0.85, p95 2.29, max 52.14 (n=1000).
- Errors: track() not ok 0; SDK WARN/ERROR lines 0; last delivery error None.
- Reconciliation with server DB: tracked ok 1000, server rows 1000, missing 0, not tracked 0, duplicate rows 0; sent on the wire 1000 events in 180 batches, IDs sent more than once 0, batches not 200: 0, ACK statuses {'accepted': 1000}.
- Queue drain: {'seconds': 0.06, 'queued_after': 0, 'queued_at_injection_end': 16}.
- Files (bytes): {'at_injection_end': {'installation-id': 36, 'queue.db': 36864, 'queue.db-shm': 32768, 'queue.db-wal': 502672}, 'after_drain': {'installation-id': 36, 'queue.db': 36864, 'queue.db-shm': 32768, 'queue.db-wal': 502672}}.
- Memory (KiB): {'pss_after_init_to_injection_end': 3934, 'pss_after_init_to_after_drain': 2702, 'heap_after_init_to_after_drain': 16, 'pss_absolute': {'before_init': 38209, 'after_init': 38582, 'injection_end': 42516, 'after_drain': 41284}}.
- Host: Apple M2 Pro, 12 CPUs, load average before { 5.35 5.92 6.98 }, after { 6.02 6.06 7.01 }. Run 2026-10-12T16:24:41Z to 2026-10-12T16:24:53Z, git edaa14c (dirty).

### signaldock-api37 (API 37) - normal: **pass**

- Device: Google sdk_gphone16k_arm64, Android 17 (API 37), arm64-v8a. Build: debug androidTest APK: SDK library debug variant, debuggable, no R8/minify (ADR-0014).
- Injection: target 1 events/s, actual 1.0 events/s over 59.0 s (60 of 60 calls); max calls in flight 1; start lateness {'p95': 8.7, 'max': 10.1}; event size 1024 bytes.
- track() completion (ms): p50 1.58, p95 2.23, max 20.52 (n=60).
- Errors: track() not ok 0; SDK WARN/ERROR lines 0; last delivery error None.
- Reconciliation with server DB: tracked ok 60, server rows 60, missing 0, not tracked 0, duplicate rows 0; sent on the wire 60 events in 12 batches, IDs sent more than once 0, batches not 200: 0, ACK statuses {'accepted': 60}.
- Queue drain: {'seconds': 2.02, 'queued_after': 0, 'queued_at_injection_end': 7}.
- Files (bytes): {'at_injection_end': {'installation-id': 36, 'queue.db': 36864, 'queue.db-shm': 32768, 'queue.db-wal': 424392}, 'after_drain': {'installation-id': 36, 'queue.db': 36864, 'queue.db-shm': 32768, 'queue.db-wal': 424392}}.
- Memory (KiB): {'pss_after_init_to_injection_end': 2360, 'pss_after_init_to_after_drain': 2008, 'heap_after_init_to_after_drain': -176, 'pss_absolute': {'before_init': 39302, 'after_init': 38674, 'injection_end': 41034, 'after_drain': 40682}}.
- Host: Apple M2 Pro, 12 CPUs, load average before { 6.97 9.63 8.57 }, after { 6.75 9.08 8.44 }. Run 2026-10-12T16:15:30Z to 2026-10-12T16:16:33Z, git 5b369f4 (dirty).

### signaldock-api37 (API 37) - offline: **pass**

- Device: Google sdk_gphone16k_arm64, Android 17 (API 37), arm64-v8a. Build: debug androidTest APK: SDK library debug variant, debuggable, no R8/minify (ADR-0014).
- Injection: target back to back events/s, actual 1175.22 events/s over 0.85 s (1000 of 1000 calls); max calls in flight 1; start lateness None; event size 1026 bytes.
- track() completion (ms): p50 0.64, p95 2.35, max 16.78 (n=1000).
- Errors: track() not ok 0; SDK WARN/ERROR lines 0; last delivery error NETWORK.
- Reconciliation with server DB: tracked ok 1000, server rows 1000, missing 0, not tracked 0, duplicate rows 0; sent on the wire 1000 events in 20 batches, IDs sent more than once 0, batches not 200: 0, ACK statuses {'accepted': 1000}.
- Queue drain: {'seconds': 16.8, 'queued_after': 0, 'queued_at_injection_end': 1000, 'persisted_retry_wait_at_start_s': 0, 'seconds_after_retry_wait': 16.8}.
- Files (bytes): {'after_store': {'installation-id': 36, 'queue.db': 1437696, 'queue.db-shm': 32768, 'queue.db-wal': 444992}, 'after_drain': {'installation-id': 36, 'queue.db': 626688, 'queue.db-shm': 32768, 'queue.db-wal': 473832}}.
- Memory (KiB): {'store_process': {'pss_after_init_to_after_store': 3384, 'heap_after_init_to_after_store': -160}, 'drain_process': {'pss_before_init_to_after_drain': 1208, 'heap_before_init_to_after_drain': 512}, 'pss_absolute': {'store_after_store': 42134, 'drain_after_drain': 40488}}.
- Host: Apple M2 Pro, 12 CPUs, load average before { 6.75 9.08 8.44 }, after { 6.03 8.74 8.33 }. Run 2026-10-12T16:16:33Z to 2026-10-12T16:16:55Z, git 5b369f4 (dirty).
