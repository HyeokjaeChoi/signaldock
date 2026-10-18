# 73. Real-device run of the SDK instrumented tests

Status: claimed
Type: task
Blocked by: None
Execution: In an open PR (2026-10-18 UTC)

## Scope and completion criteria

ADR-0023 records only CI emulator runs, and the local emulator run did not pass. Run the SDK instrumented tests on a physical Android device and record the result.

Completion criteria:
1. `sdk/gradlew -p sdk :sdk-android:connectedDebugAndroidTest --no-daemon` runs on a physical device.
2. The result is recorded here.

## Result

- Date: 2026-10-18 UTC. Commit: `55c3339` (main). Host: macOS, JDK 21 from `.tools/`.
- Device: Samsung SM-F766N (Galaxy Z Flip7), Android 16 (API 36), USB, via `adb devices -l`. No emulator was used.
- Command: `sdk/gradlew -p sdk :sdk-android:connectedDebugAndroidTest --no-daemon`. `BUILD SUCCESSFUL` in 3 min 31 s.
- Gradle: "Finished 49 tests on SM-F766N - 16". 0 failed. 16 skipped: the phase tests of `DurabilityPhases`, `FaultPhases` and `MeasurePhases` skip themselves without `-e phase` (ADR-0011 decision 4). 33 ran and passed.
- Not run: the process-boundary script `scripts/device-durability.sh` (its cases switch airplane mode and fill the data partition; ADR-0029 says to use a wipeable emulator, not a personal phone).
