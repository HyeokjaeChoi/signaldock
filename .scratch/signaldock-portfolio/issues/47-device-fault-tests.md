# 47. Device fault tests: airplane mode, full disk, WorkManager failure, power-loss semantics

Status: claimed
Type: task
Blocked by: None
Execution: Implemented on branch `test/device-faults`; the decisions are in ADR-0029.

## Scope and completion criteria

The device test harness (ADR-0011) tested offline only as a refused connection, never produced a real full disk or a real WorkManager failure, and could not test power loss.

1. Airplane mode phase: track while the network is off, assert `NETWORK` and the queue, restore the network, assert delivery recovers. The network is always restored.
2. Real full disk phase: fill `/data` until `track()` returns `StorageError`, assert the queue and `integrity_check`, free the space, assert `track()` and delivery recover. The filler file is always deleted.
3. WorkManager failure phase: `initialize()` and `track()` succeed, the event is stored, a WARN is logged, foreground delivery works.
4. Power loss: no test. Document the semantics and pin `PRAGMA synchronous` and `journal_mode` with a test.
5. Update the limits in ADR-0011 and ADR-0017. Run on an emulator and record the results.

Verification: `scripts/device-durability.sh <emulator> airplane fulldisk workmanager`; `am instrument -e class local.signaldock.device.PowerLossSemanticsDeviceTest`; `scripts/check-repo-rules.sh`; `sdk/gradlew -p sdk :sdk-android:assembleDebugAndroidTest`.
