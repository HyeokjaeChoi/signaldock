# 85. Device verification of SDK 0.3.0 on a physical phone

Status: resolved
Type: task
Blocked by: None
Execution: Done (PR #204, 2026-10-24 UTC)

## Scope and completion criteria

Issue 73 ran the instrumented tests on a phone with SDK 0.2.0 code. The device measurements and the host-app cost were never run on a phone. Run them on the phone with SDK 0.3.0 and record the result.

Completion criteria:
1. The instrumented tests run on the phone (`connectedDebugAndroidTest`).
2. `scripts/device-measure.sh` runs the three debug scenarios on the phone (ADR-0042).
3. `scripts/host-cost-measure.sh` runs on the phone (ADR-0042).
4. The result is recorded here, and the docs that said "no physical device" point here.

## Result

All runs: 2026-10-24 UTC. Device: Samsung SM-F766N (Galaxy Z Flip7), Android 17 (API 37), USB. SDK 0.3.0. Tests ran at commit `c96939b`. Measurements ran at commit `049f7ed`.

1. Instrumented tests: `sdk/gradlew -p sdk :sdk-android:connectedDebugAndroidTest --no-daemon`. `BUILD SUCCESSFUL` in 3 min 32 s. Gradle: "Finished 49 tests on SM-F766N - 17". The XML report has 33 test cases: 17 passed, 0 failed, 16 skipped. The skipped cases are the phase tests (`DurabilityPhases` 9, `FaultPhases` 3, `MeasurePhases` 4). They skip themselves without `-e phase` (ADR-0011 decision 4). Suites that ran: `CancellationDeviceTest` 4, `CapacityDeviceTest` 5, `ConcurrencyDeviceTest` 4, `InitDeviceTest` 3, `PowerLossSemanticsDeviceTest` 1.
2. Device measurement: `SIGNALDOCK_PHYSICAL_DEVICE=1 scripts/device-measure.sh <phone-serial>` (debug build, ADR-0042). Results: `perf/sdk-results/api37-phone-{normal,offline,burst}/`. All three passed with 0 missing, 0 duplicate, and 0 failed `track()` calls.
3. Host-app cost: `SIGNALDOCK_PHYSICAL_DEVICE=1 scripts/host-cost-measure.sh <phone-serial>`. Results: `perf/sdk-results/host-cost-phone-api37/`. One without-SDK PSS run is an outlier (16794 KiB, median 40693 KiB). The startup paired difference (-25 to +47 ms) is within noise.

### Phone and emulator

Emulator rows: `perf/sdk-results/api37-*/` (2026-10-12, commits `5b369f4`, `3e7d160`, `edaa14c`, dirty tree) and `perf/sdk-results/host-cost-api37/` (2026-10-14, SDK 0.1.0). The phone numbers are not comparable with the emulator numbers (ADR-0042). The emulator ran on a shared host.

| Metric | Emulator API 37 | Phone API 37 |
| --- | --- | --- |
| normal: `track()` p50 / p95 / max ms | 1.58 / 2.23 / 20.52 | 32.22 / 48.15 / 82.38 |
| normal: drain s | 2.02 | 2.19 |
| burst: `track()` p50 / p95 / max ms | 0.85 / 2.29 / 52.14 | 9.02 / 18.02 / 93.16 |
| burst: drain s | 0.06 | 0.15 |
| offline: actual events/s | 888.31 | 96.1 |
| offline: `track()` p50 / p95 / max ms | 0.85 / 2.7 / 24.42 | 9.64 / 16.48 / 74.45 |
| offline: drain s | 16.57 | 69.72 |
| Host cost: startup `am start -W` p50, without / with SDK ms | 235 / 238 | 82.5 / 88.5 |
| Host cost: PSS delta 5 s after start, median KiB | 4014 | 15548.5 (min 14613, max 38958; outlier) |
| Host cost: PSS delta after 500 `track()`, median KiB | 16532.5 | 27495.5 |
| Host cost: APK delta bytes | +616829 | +623449 |

### Test counts

Gradle prints "Starting 33 tests" and, at the end, "Finished 49 tests". The XML report and the progress counter (`N/33`) have 33 test cases in all: 17 passed and 16 skipped. The log has 16 `SKIPPED` lines. So 49 is most likely 33 plus the 16 skipped counted a second time (inferred from the sums; Gradle's source was not read). Use the XML count. Issue 73 used 49 and wrote "33 ran and passed"; that was wrong, and issue 73 carries a correction (issue 86).

### Reading the numbers

- `track()` is a suspend function: the caller waits for it in a coroutine (integration guide section 3). Its time is the wait for the durable commit to the queue. On the phone it is about 10 to 20 times the emulator time. The likely causes are flash write cost under `synchronous=FULL` and CPU idle states (the 1-per-second `normal` scenario is slower per call than `burst`). Gap: this was not measured further.
- The host-cost emulator row is SDK 0.1.0 and the phone row is SDK 0.3.0, so the deltas differ by device and by SDK version.
- `host-cost-phone-api37/summary.md` says "(dirty)" because untracked result files existed during the run. The scripts and the SDK were the committed `049f7ed`.
- The results do not record the phone serial or build fingerprint (personal device).

## Not run

- `scripts/device-durability.sh`. It changes backup, airplane mode, and disk state. ADR-0042 keeps it emulator-only.
- The release (R8) device measurement. It needs `adb root`, which a phone does not give (ADR-0042).
- The Baseline Profile benchmark on the phone.
- The fault and durability phase cases (they need `-e phase`).
