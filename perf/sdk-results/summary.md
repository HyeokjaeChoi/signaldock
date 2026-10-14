# SDK device measurement summary (issue 24)

Measured in this environment (emulators, Compose server on one Mac). Not an SLA. A run that missed the target injection rate is `invalid`, never `pass` (spec section 7). One run per AVD, build type and scenario. Method and criteria: ADR-0014 (debug) and ADR-0018 (release, R8).

| AVD | Build | Scenario | Verdict | Target / actual events/s | Max in flight | track() p50 / p95 / max ms | Not ok | Missing / dup rows | Drain s | queue.db + wal + shm bytes (after drain) | PSS delta KiB |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| signaldock-api24 | debug | burst | pass | 100 / 99.97 | 1 | 1.15 / 2.25 / 7.0 | 0 | 0 / 0 | 0.06 | 57344 + wal 436752 + shm 32768 | 6797 |
| signaldock-api24 | debug | normal | pass | 1 / 1.0 | 1 | 1.87 / 3.8 / 7.08 | 0 | 0 / 0 | 0.92 | 36864 + wal 420272 + shm 32768 | 7032 |
| signaldock-api24 | debug | offline | pass | back to back / 1128.22 | 1 | 0.75 / 1.9 / 7.78 | 0 | 0 / 0 | 16.37 | 626688 + wal 524288 + shm 32768 | 7713 |
| signaldock-api37 | debug | burst | pass | 100 / 99.99 | 4 | 0.85 / 2.29 / 52.14 | 0 | 0 / 0 | 0.06 | 36864 + wal 502672 + shm 32768 | 2702 |
| signaldock-api37 | debug | normal | pass | 1 / 1.0 | 1 | 1.58 / 2.23 / 20.52 | 0 | 0 / 0 | 2.02 | 36864 + wal 424392 + shm 32768 | 2008 |
| signaldock-api37 | debug | offline | pass | back to back / 888.31 | 1 | 0.85 / 2.7 / 24.42 | 0 | 0 / 0 | 16.57 | 479232 + wal 523272 + shm 32768 | 3498 |
| signaldock-api37 | release | burst | pass | 100 / 99.98 | 1 | 0.48 / 1.07 / 5.37 | 0 | 0 / 0 | 0.06 | 36864 + wal 436752 + shm 32768 | 3436 |
| signaldock-api37 | release | normal | pass | 1 / 1.0 | 1 | 0.77 / 1.23 / 3.51 | 0 | 0 / 0 | 1.49 | 36864 + wal 424392 + shm 32768 | 1389 |
| signaldock-api37 | release | offline | pass | back to back / 2103.4 | 1 | 0.33 / 1.16 / 7.37 | 0 | 0 / 0 | 17.2 | 622592 + wal 444992 + shm 32768 | 2667 |

Offline row: track() numbers are the offline store phase (endpoint unreachable, calls back to back); drain seconds are the restarted process after the persisted retry wait (see the run details).

### signaldock-api24 (API 24) - burst, debug build: **pass**

- Device: Google Android SDK built for arm64, Android 7.0 (API 24), arm64-v8a. Build: debug androidTest APK: SDK library debug variant, debuggable, no R8/minify (ADR-0014).
- Injection: target 100 events/s, actual 99.97 events/s over 9.99 s (1000 of 1000 calls); max calls in flight 1; start lateness {'p95': 3.6, 'max': 4.5}; event size 1022 bytes.
- track() completion (ms): p50 1.15, p95 2.25, max 7.0 (n=1000).
- Errors: track() not ok 0; SDK WARN/ERROR lines 0; last delivery error None.
- Reconciliation with server DB: tracked ok 1000, server rows 1000, missing 0, not tracked 0, duplicate rows 0; sent on the wire 1000 events in 265 batches, IDs sent more than once 0, batches not 200: 0, ACK statuses {'accepted': 1000}.
- Queue drain: {'seconds': 0.06, 'queued_after': 0, 'queued_at_injection_end': 18}.
- Files (bytes): {'at_injection_end': {'installation-id': 36, 'queue.db': 57344, 'queue.db-shm': 32768, 'queue.db-wal': 436752}, 'after_drain': {'installation-id': 36, 'queue.db': 57344, 'queue.db-shm': 32768, 'queue.db-wal': 436752}}.
- Memory (KiB): {'pss_after_init_to_injection_end': 7929, 'pss_after_init_to_after_drain': 6797, 'heap_after_init_to_after_drain': 2939, 'pss_absolute': {'before_init': 19405, 'after_init': 23154, 'injection_end': 31083, 'after_drain': 29951}}.
- Host: Apple M2 Pro, 12 CPUs, load average before { 4.62 7.35 7.83 }, after { 4.52 7.24 7.79 }. Run 2026-10-12T16:18:46Z to 2026-10-12T16:18:58Z, git 5b369f4 (dirty).

### signaldock-api24 (API 24) - normal, debug build: **pass**

- Device: Google Android SDK built for arm64, Android 7.0 (API 24), arm64-v8a. Build: debug androidTest APK: SDK library debug variant, debuggable, no R8/minify (ADR-0014).
- Injection: target 1 events/s, actual 1.0 events/s over 59.01 s (60 of 60 calls); max calls in flight 1; start lateness {'p95': 9.8, 'max': 10.3}; event size 1024 bytes.
- track() completion (ms): p50 1.87, p95 3.8, max 7.08 (n=60).
- Errors: track() not ok 0; SDK WARN/ERROR lines 0; last delivery error None.
- Reconciliation with server DB: tracked ok 60, server rows 60, missing 0, not tracked 0, duplicate rows 0; sent on the wire 60 events in 12 batches, IDs sent more than once 0, batches not 200: 0, ACK statuses {'accepted': 60}.
- Queue drain: {'seconds': 0.92, 'queued_after': 0, 'queued_at_injection_end': 7}.
- Files (bytes): {'at_injection_end': {'installation-id': 36, 'queue.db': 36864, 'queue.db-shm': 32768, 'queue.db-wal': 420272}, 'after_drain': {'installation-id': 36, 'queue.db': 36864, 'queue.db-shm': 32768, 'queue.db-wal': 420272}}.
- Memory (KiB): {'pss_after_init_to_injection_end': 6596, 'pss_after_init_to_after_drain': 7032, 'heap_after_init_to_after_drain': 4472, 'pss_absolute': {'before_init': 18515, 'after_init': 22401, 'injection_end': 28997, 'after_drain': 29433}}.
- Host: Apple M2 Pro, 12 CPUs, load average before { 5.30 8.34 8.20 }, after { 4.47 7.56 7.92 }. Run 2026-10-12T16:17:23Z to 2026-10-12T16:18:24Z, git 5b369f4 (dirty).

### signaldock-api24 (API 24) - offline, debug build: **pass**

- Device: Google Android SDK built for arm64, Android 7.0 (API 24), arm64-v8a. Build: debug androidTest APK: SDK library debug variant, debuggable, no R8/minify (ADR-0014).
- Injection: target back to back events/s, actual 1128.22 events/s over 0.89 s (1000 of 1000 calls); max calls in flight 1; start lateness None; event size 1026 bytes.
- track() completion (ms): p50 0.75, p95 1.9, max 7.78 (n=1000).
- Errors: track() not ok 0; SDK WARN/ERROR lines 0; last delivery error NETWORK.
- Reconciliation with server DB: tracked ok 1000, server rows 1000, missing 0, not tracked 0, duplicate rows 0; sent on the wire 1000 events in 20 batches, IDs sent more than once 0, batches not 200: 0, ACK statuses {'accepted': 1000}.
- Queue drain: {'seconds': 16.37, 'queued_after': 0, 'queued_at_injection_end': 1000, 'persisted_retry_wait_at_start_s': 4.5, 'seconds_after_retry_wait': 11.88}.
- Files (bytes): {'after_store': {'installation-id': 36, 'queue.db': 1437696, 'queue.db-shm': 32768, 'queue.db-wal': 432632}, 'after_drain': {'installation-id': 36, 'queue.db': 626688, 'queue.db-shm': 32768, 'queue.db-wal': 524288}}.
- Memory (KiB): {'store_process': {'pss_after_init_to_after_store': 7713, 'heap_after_init_to_after_store': 2965}, 'drain_process': {'pss_before_init_to_after_drain': 10253, 'heap_before_init_to_after_drain': -696}, 'pss_absolute': {'store_after_store': 30456, 'drain_after_drain': 28788}}.
- Host: Apple M2 Pro, 12 CPUs, load average before { 7.43 6.25 6.37 }, after { 6.53 6.12 6.32 }. Run 2026-10-12T16:36:13Z to 2026-10-12T16:36:34Z, git 3e7d160 (dirty).

### signaldock-api37 (API 37) - burst, debug build: **pass**

- Device: Google sdk_gphone16k_arm64, Android 17 (API 37), arm64-v8a. Build: debug androidTest APK: SDK library debug variant, debuggable, no R8/minify (ADR-0014).
- Injection: target 100 events/s, actual 99.99 events/s over 9.99 s (1000 of 1000 calls); max calls in flight 4; start lateness {'p95': 2.7, 'max': 6.4}; event size 1022 bytes.
- track() completion (ms): p50 0.85, p95 2.29, max 52.14 (n=1000).
- Errors: track() not ok 0; SDK WARN/ERROR lines 0; last delivery error None.
- Reconciliation with server DB: tracked ok 1000, server rows 1000, missing 0, not tracked 0, duplicate rows 0; sent on the wire 1000 events in 180 batches, IDs sent more than once 0, batches not 200: 0, ACK statuses {'accepted': 1000}.
- Queue drain: {'seconds': 0.06, 'queued_after': 0, 'queued_at_injection_end': 16}.
- Files (bytes): {'at_injection_end': {'installation-id': 36, 'queue.db': 36864, 'queue.db-shm': 32768, 'queue.db-wal': 502672}, 'after_drain': {'installation-id': 36, 'queue.db': 36864, 'queue.db-shm': 32768, 'queue.db-wal': 502672}}.
- Memory (KiB): {'pss_after_init_to_injection_end': 3934, 'pss_after_init_to_after_drain': 2702, 'heap_after_init_to_after_drain': 16, 'pss_absolute': {'before_init': 38209, 'after_init': 38582, 'injection_end': 42516, 'after_drain': 41284}}.
- Host: Apple M2 Pro, 12 CPUs, load average before { 5.35 5.92 6.98 }, after { 6.02 6.06 7.01 }. Run 2026-10-12T16:24:41Z to 2026-10-12T16:24:53Z, git edaa14c (dirty).

### signaldock-api37 (API 37) - normal, debug build: **pass**

- Device: Google sdk_gphone16k_arm64, Android 17 (API 37), arm64-v8a. Build: debug androidTest APK: SDK library debug variant, debuggable, no R8/minify (ADR-0014).
- Injection: target 1 events/s, actual 1.0 events/s over 59.0 s (60 of 60 calls); max calls in flight 1; start lateness {'p95': 8.7, 'max': 10.1}; event size 1024 bytes.
- track() completion (ms): p50 1.58, p95 2.23, max 20.52 (n=60).
- Errors: track() not ok 0; SDK WARN/ERROR lines 0; last delivery error None.
- Reconciliation with server DB: tracked ok 60, server rows 60, missing 0, not tracked 0, duplicate rows 0; sent on the wire 60 events in 12 batches, IDs sent more than once 0, batches not 200: 0, ACK statuses {'accepted': 60}.
- Queue drain: {'seconds': 2.02, 'queued_after': 0, 'queued_at_injection_end': 7}.
- Files (bytes): {'at_injection_end': {'installation-id': 36, 'queue.db': 36864, 'queue.db-shm': 32768, 'queue.db-wal': 424392}, 'after_drain': {'installation-id': 36, 'queue.db': 36864, 'queue.db-shm': 32768, 'queue.db-wal': 424392}}.
- Memory (KiB): {'pss_after_init_to_injection_end': 2360, 'pss_after_init_to_after_drain': 2008, 'heap_after_init_to_after_drain': -176, 'pss_absolute': {'before_init': 39302, 'after_init': 38674, 'injection_end': 41034, 'after_drain': 40682}}.
- Host: Apple M2 Pro, 12 CPUs, load average before { 6.97 9.63 8.57 }, after { 6.75 9.08 8.44 }. Run 2026-10-12T16:15:30Z to 2026-10-12T16:16:33Z, git 5b369f4 (dirty).

### signaldock-api37 (API 37) - offline, debug build: **pass**

- Device: Google sdk_gphone16k_arm64, Android 17 (API 37), arm64-v8a. Build: debug androidTest APK: SDK library debug variant, debuggable, no R8/minify (ADR-0014).
- Injection: target back to back events/s, actual 888.31 events/s over 1.12 s (1000 of 1000 calls); max calls in flight 1; start lateness None; event size 1026 bytes.
- track() completion (ms): p50 0.85, p95 2.7, max 24.42 (n=1000).
- Errors: track() not ok 0; SDK WARN/ERROR lines 0; last delivery error NETWORK.
- Reconciliation with server DB: tracked ok 1000, server rows 1000, missing 0, not tracked 0, duplicate rows 0; sent on the wire 1000 events in 20 batches, IDs sent more than once 0, batches not 200: 0, ACK statuses {'accepted': 1000}.
- Queue drain: {'seconds': 16.57, 'queued_after': 0, 'queued_at_injection_end': 1000, 'persisted_retry_wait_at_start_s': 3.5, 'seconds_after_retry_wait': 13.02}.
- Files (bytes): {'after_store': {'installation-id': 36, 'queue.db': 1445888, 'queue.db-shm': 32768, 'queue.db-wal': 449112}, 'after_drain': {'installation-id': 36, 'queue.db': 479232, 'queue.db-shm': 32768, 'queue.db-wal': 523272}}.
- Memory (KiB): {'store_process': {'pss_after_init_to_after_store': 3498, 'heap_after_init_to_after_store': -112}, 'drain_process': {'pss_before_init_to_after_drain': 1238, 'heap_before_init_to_after_drain': 464}, 'pss_absolute': {'store_after_store': 42239, 'drain_after_drain': 40603}}.
- Host: Apple M2 Pro, 12 CPUs, load average before { 6.90 6.00 6.29 }, after { 7.05 6.11 6.33 }. Run 2026-10-12T16:35:33Z to 2026-10-12T16:35:55Z, git 3e7d160 (dirty).

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

### signaldock-api37 (API 37) - normal, release build: **pass**

- Device: Google sdk_gphone16k_arm64, Android 17 (API 37), arm64-v8a. Build: release measure app (sdk/measure-app): SDK as dependency, R8 minify and shrinkResources on, debug-signed release variant, not debuggable (ADR-0018).
- Injection: target 1 events/s, actual 1.0 events/s over 59.0 s (60 of 60 calls); max calls in flight 1; start lateness {'p95': 1.8, 'max': 4.6}; event size 1024 bytes.
- track() completion (ms): p50 0.77, p95 1.23, max 3.51 (n=60).
- Errors: track() not ok 0; SDK WARN/ERROR lines 0; last delivery error None.
- Reconciliation with server DB: tracked ok 60, server rows 60, missing 0, not tracked 0, duplicate rows 0; sent on the wire 60 events in 11 batches, IDs sent more than once 0, batches not 200: 0, ACK statuses {'accepted': 60}.
- Queue drain: {'seconds': 1.49, 'queued_after': 0, 'queued_at_injection_end': 6}.
- Files (bytes): {'at_injection_end': {'installation-id': 36, 'queue.db': 36864, 'queue.db-shm': 32768, 'queue.db-wal': 424392}, 'after_drain': {'installation-id': 36, 'queue.db': 36864, 'queue.db-shm': 32768, 'queue.db-wal': 424392}}.
- Memory (KiB): {'pss_after_init_to_injection_end': 2285, 'pss_after_init_to_after_drain': 1389, 'heap_after_init_to_after_drain': -240, 'pss_absolute': {'before_init': 9809, 'after_init': 11424, 'injection_end': 13709, 'after_drain': 12813}}.
- Host: Apple M2 Pro, 12 CPUs, load average before { 13.23 7.53 6.18 }, after { 8.05 7.07 6.10 }. Run 2026-10-14T00:34:37Z to 2026-10-14T00:35:38Z, git 79ee88f (dirty).

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
