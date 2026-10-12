# 12. SDK internal packages per concern

Status: ready-for-agent
Type: task
Blocked by: 11
Execution: Done (merged in PR #22, 2026-10-11)

## Scope and completion criteria

Move `local.signaldock.internal` code into the packages that ADR-0005, section "SDK: one publication module, feature packages", lists. No behavior change. Public API stays in `local.signaldock`.

Done when SDK tests and `scripts/verify-contract.sh` pass, with commands and results recorded here.

## Comments

2026-10-11: Created with ADR-0005.

2026-10-11: Implemented. Package map (all under `local.signaldock.internal`; no behavior or API change):

| Old file / type | New location |
|---|---|
| `InitCoordinator.kt` | `init` |
| `Infra.kt`: `Logger`, `AndroidLogger`, `Clock`, `IdGenerator`, `RandomUuidV4` | `init/Infra.kt` |
| `Infra.kt`: `SDK_VERSION`, `CONTRACT_VERSION` | `transport/Versions.kt` |
| `Infra.kt`: `InstallationIdStore` | `storage/InstallationIdStore.kt` |
| `Validation.kt` | `validation` |
| `Transport.kt` | `transport` |
| `Storage.kt`: entities, `QueueDao`, `QueueLimits`, `QueueUsage`, `AddOutcome`, `DiagnosticsSnapshot` | `storage/Storage.kt` |
| `Storage.kt`: `SignalDockDatabase` | stays in `local.signaldock.internal` (`SignalDockDatabase.kt`) |
| `EventCollector.kt`: `EventSink`, `RoomEventSink` | `storage/EventSink.kt` |
| `EventCollector.kt`: `EventCollector` | stays in `local.signaldock.internal` (moved to `collection` in the follow-up below) |

Decisions:
- Room writes the schema to `schemas/<fully qualified database class name>/`. Moving `SignalDockDatabase` would rename that directory, so the class stays in place. `schemas/` is unchanged.
- `EventCollector` fits no package in ADR-0005 (it is the track path: validate, build DTO, size, store). It stays in the root package until a decision names a home. No ADR was written.
- No `delivery`, `scheduling` or `diagnostics` package: no existing code belongs there (issue 04 and later). `DiagnosticsSnapshot` is a Room query result, so it stays in `storage`.
- Tests stay in `local.signaldock` (they test the public API); only imports changed. `docs/design-index.md` has no path that became wrong.

Verification (JAVA_HOME=.tools/jdk-21.0.12.1+1, ANDROID_HOME=~/Library/Android/sdk):
- `sdk/gradlew -p sdk :sdk-android:testDebugUnitTest :sdk-android:assembleRelease --no-daemon`: BUILD SUCCESSFUL; 35 tests, 0 failures, 0 skipped.
- `scripts/verify-contract.sh`: ALL CONTRACT CHECKS PASSED.
- `git diff --stat main -- sdk/sdk-android/schemas`: empty.

2026-10-11: Follow-up. `EventCollector` moved to `local.signaldock.internal.collection`. ADR-0005 SDK section: added the `collection` row, added composition seams to the `init` row, added a sentence on why `SignalDockDatabase` stays in `local.signaldock.internal`. Only `SignalDockDatabase` remains in the root internal package.
- `sdk/gradlew -p sdk :sdk-android:testDebugUnitTest :sdk-android:assembleRelease --no-daemon`: BUILD SUCCESSFUL; 35 tests, 0 failures
- `scripts/verify-contract.sh`: ALL CONTRACT CHECKS PASSED
- `git diff --stat main -- sdk/sdk-android/schemas`: empty.

2026-10-12: Record reconciled. Merged into main in PR #22 on 2026-10-11. Earlier branch and push notes above describe the state before that merge.
