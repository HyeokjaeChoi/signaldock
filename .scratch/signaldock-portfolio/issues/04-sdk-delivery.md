# 04. SDK batch delivery and recovery

Status: ready-for-agent
Type: task
Blocked by: 02, 03
Execution: Implemented on branch `impl/04-sdk-delivery`; JVM tests and release assemble run; emulator, device and R8 consumer checks not run

## Scope and completion criteria

Verify Retrofit and complete ACK validation, atomic queue updates, persistent Full Jitter and Retry-After state, error holds, and contention between the shared sender and WorkManager.

Preserve the applicable contracts in the [agreed spec](../spec.md) and [final review](../final-review.md). Distinguish implementation, execution, and failure evidence. Assess completion after recording the outputs and actual verification.

## Comments

2026-10-09: Created after the full Q127 agreement.

2026-10-11: Implemented on branch `impl/04-sdk-delivery` (not pushed, no PR).

Built: Retrofit 3.0.0 / OkHttp transport (`internal.delivery`: `BatchTransport`, `Batcher`, `AckValidator`, `RetryPolicy`, `Sender`); WorkManager 2.12.0 Worker, foreground trigger, foreground-exit observer and `DeliveryCoordinator` (`internal.scheduling`); `QueueDao.applyAck` (one transaction); `hold` and `maxBatchEvents` columns in `retry_state`; canonical batch size helpers in `internal.transport`. Decisions are in `docs/adr/0006-sdk-delivery-decisions.md`.

Design sections followed: architecture "Collection, delivery, and ACK", "Lost ACKs and event deduplication", "SDK lifecycle and cancellation", "Retry and transport boundaries", "Installation lifecycle and durability", "Contracts, generated sources, and JSON"; ADR-0001, 0002, 0003 (item 7), 0005; spec sections 4, 8.A (Q57, Q72 to Q75), 8.B (Q58 to Q60, Q65 to Q68, Q105), 8.C (Q87, Q88), Q119 to Q123.

Finding: the OkHttp 5.5.0 pin cannot build at compileSdk 36 (its AAR declares `minCompileSdk=37`). The catalog now pins 5.4.0, which passes. See ADR-0006 decision 1 and TOOLCHAIN.md.

Commands and results (JDK `.tools/jdk-21.0.12.1+1`, `ANDROID_HOME=~/Library/Android/sdk`, after `scripts/setup-toolchain.sh`):
- `sdk/gradlew -p sdk :sdk-android:cleanTestDebugUnitTest :sdk-android:testDebugUnitTest :sdk-android:assembleRelease --no-daemon`: BUILD SUCCESSFUL. 89 tests, 0 failures, 0 skipped (35 existing, 54 new: `DeliveryTest` 33, `TransportTest` 9, `SchedulingTest` 12).
- `scripts/verify-contract.sh`: ALL CONTRACT CHECKS PASSED.

Evidence class: implementation and JVM execution evidence only (Robolectric SDK 36, MockWebServer on localhost, `work-testing`, fake clock and transport). Not run: emulator or device, real process restart, real WorkManager execution and constraints, R8 consumer app with Retrofit, status follow-ups inside one OkHttp Call, a second process. No performance measurement.
