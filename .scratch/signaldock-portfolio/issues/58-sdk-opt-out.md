# 58. SDK opt-out and clearQueue

Status: claimed
Type: task
Blocked by: None
Execution: Branch `impl/sdk-opt-out` (PR open; not merged). ADR-0034, spec Q156.

## Scope and completion criteria

Spec Q156 (option a). The Android SDK gets an opt-out switch and a queue clear.

1. `setOptOut(true)`: `track()` stores nothing and returns `TrackResult.OptedOut`. Nothing is sent for those events.
2. Queued events stay; `clearQueue()` deletes them.
3. The state persists across restarts in backup-excluded SDK storage.
4. Public API dump, integration guide (both languages), CHANGELOG `[Unreleased]`, and engineering documents (they list the opt-out as a gap) are updated in the same PR.
5. Tests: JVM tests with Robolectric (`OptOutTest`). Gap: no device test of a real process restart.

Verification: `sdk/gradlew -p sdk :sdk-android:test`; `sdk/gradlew -p sdk :sdk-android:apiCheck`; `scripts/check-repo-rules.sh`; `scripts/verify-contract.sh`; `coderabbit review --base main --deep --agent`.

## Comments
