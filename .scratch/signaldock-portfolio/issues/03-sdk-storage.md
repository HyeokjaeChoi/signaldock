# 03. SDK initialization and durable collection

Status: ready-for-agent
Type: task
Blocked by: 01
Execution: Implemented on branch impl/03-sdk-storage (not pushed). 30 JVM tests (Robolectric + real Room) and scripts/verify-contract.sh pass. Not run: emulator or device checks, real process restart, real backup/restore, R8.

## Scope and completion criteria

Verify shared initialization and cancellation, typed track and validation, the Room queue and its limits, UUIDv4 and backup exclusion, and diagnostics.

Preserve the applicable contracts in the [agreed spec](../spec.md) and [final review](../final-review.md). Distinguish implementation, execution, and failure evidence. Assess completion after recording the outputs and actual verification.

## Comments

2026-10-09: Created after the full Q127 agreement.

2026-10-11: Implementation (branch `impl/03-sdk-storage`, not pushed).

Built (module `sdk/sdk-android`, package `local.signaldock`):
- Shared initialization: `SignalDock.initialize(context, config): InitResult`. Same config shares one attempt in an SDK-owned process scope; a waiter's cancellation cancels only its own wait; different config returns `ConfigurationConflict`; failed attempts are not repeated automatically, and a new call retries while keeping queue and installation ID. Invalid config (blank key, bad endpoint, non-positive/infinite timeouts) creates no state.
- Typed `track(name) { property(...) }` with validation (empty/whitespace names and keys, NUL, lone surrogates, duplicate keys, NaN/Infinity, integers beyond +-(2^53-1) also via Double, 16 KiB event limit in UTF-8 JSON bytes). No trimming or normalization. `CancellationException` propagates; WARN only when cancelled during storage.
- Room queue (`events`, `rejections` last 100 without payload, `retry_state`), atomic limit check and insert (10,000 events / 16 MiB), `QueueFull` keeps existing events. Schema v1 exported to `sdk/sdk-android/schemas/`.
- UUIDv4 event ids; installation ID file, queue DB and sidecars all under `noBackupFilesDir/signaldock/`.
- `diagnostics()`: queue count/bytes/oldest, recent rejections, last ACK, failure count, next attempt.
- KSP pinned to 2.3.12 (`gradle/libs.versions.toml`); decisions recorded in `docs/adr/0003-sdk-storage-decisions.md`.
- Not in this issue: delivery, sender, ACK application, WorkManager, foreground triggers (issue 04).

Commands run (from the worktree root, JDK = `.tools/jdk-17.0.20.1+1`, Android SDK `~/Library/Android/sdk`):
- `scripts/setup-toolchain.sh`: installed JDK, oasdiff, openapi-generator.
- `JAVA_HOME=.tools/jdk-17.0.20.1+1 sdk/gradlew -p sdk :sdk-android:testDebugUnitTest --no-daemon`: BUILD SUCCESSFUL; 30 tests, 0 failures (LifecycleTest 10, StorageTest 7, TrackTest 13).
- `scripts/verify-contract.sh`: ALL CONTRACT CHECKS PASSED (includes `sdk` Gradle build evaluation).

Not run: emulator/device tests; a real process restart (the 1,000-event test re-opens the same Room files in one JVM); backup exclusion on a real device (tests assert file locations under `noBackupFilesDir` only); R8; any HTTP delivery.

2026-10-11: CodeRabbit review round fixes (second commit on `impl/03-sdk-storage`).
- Long.MIN_VALUE: `property(key, Long)` now uses a range check instead of `abs()`; TrackTest covers `Long.MIN_VALUE` and `Long.MAX_VALUE`.
- Initialization: `runAttempt` maps every non-cancellation `Exception` to `InitError.StorageFailure`; `CancellationException` is rethrown. New test: a `SecurityException` during storage creation returns a failure result and a later call succeeds.
- R8 claim: true for the AGP 8.13.2 bundled R8 (Kotlin 2.4 needs R8 9.1.29 per developer.android.com/build/kotlin-support; effect is metadata-parse warnings, not build failure), but it does not apply to this unminified AAR. No build change; reason, sources and the requirement for the consumer R8 test are in ADR-0003 item 9.
- `JAVA_HOME=.tools/jdk-17.0.20.1+1 sdk/gradlew -p sdk :sdk-android:testDebugUnitTest :sdk-android:assembleRelease --no-daemon`: BUILD SUCCESSFUL; 31 tests, 0 failures (LifecycleTest 11, StorageTest 7, TrackTest 13). `assembleRelease` ran without minification.
- `scripts/verify-contract.sh`: ALL CONTRACT CHECKS PASSED.
- Not run: R8-shrunk consumer build (distribution issue).
