# 58. SDK opt-out and clearQueue

Status: resolved
Type: task
Blocked by: None
Execution: Done (merged in PR #159, 2026-10-17 02:50:47 UTC)

## Scope and completion criteria

Spec Q156 (option a). The Android SDK gets an opt-out switch and a queue clear.

1. `setOptOut(true)`: `track()` stores nothing and returns `TrackResult.OptedOut`. Nothing is sent for those events.
2. Queued events stay; `clearQueue()` deletes them.
3. The state persists across restarts in backup-excluded SDK storage.
4. Public API dump, integration guide (both languages), CHANGELOG `[Unreleased]`, and engineering documents (they list the opt-out as a gap) are updated in the same PR.
5. Tests: JVM tests with Robolectric (`OptOutTest`). Gap: no device test of a real process restart.

Verification: `sdk/gradlew -p sdk :sdk-android:test`; `sdk/gradlew -p sdk :sdk-android:apiCheck`; `scripts/check-repo-rules.sh`; `scripts/verify-contract.sh`; `coderabbit review --base main --deep --agent`.

## Comments

- 2026-10-17 UTC: PR #159 merged 02:50:47Z (ADR-0034, spec Q156). Local checks in the PR: `:sdk-android:test` (OptOutTest 8 tests), `apiCheck`, `scripts/check-repo-rules.sh`, `scripts/verify-contract.sh` passed.
- Review rounds (PR #159): `coderabbit review` 1 minor (catch all exceptions in persist), fixed. PR review fixed: `clearQueue` null handling; Major: `setOptOut` reports success only if the opt-out was persisted; resume sample uses the Boolean result. 2 Trivial low-value nitpicks (retry state after `clearQueue`, non-root test permission) were not applied.
- Gap: no device test of a real process restart with the marker file; the CI device-test job runs existing androidTest classes only (PR #159).
