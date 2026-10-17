# 35. Public API check and CHANGELOG

Status: resolved
Type: task
Blocked by: None
Execution: Done (merged in PR #89, 2026-10-14 UTC)

## Scope and completion criteria

A change to the SDK public API can break apps, and a release has no list of changes. Add a check and a changelog.

1. A public API check for `sdk/sdk-android` (`local.signaldock`: `SignalDock.kt`, `Results.kt`, `EventBuilder.kt`). The committed dump fails the check when the public API changes. The check runs in an existing gate, not a new CI job. The tool choice and the update command are in ADR-0019. `docs/design-index.md` tells implementers to update the dump, the integration guide, and the changelog in the same PR.
2. `CHANGELOG.md` (Keep a Changelog) with `[Unreleased]` and `[0.1.0] - 2026-10-14`, linked from `README.md` and `README.ko.md`.

Verification:
- `sdk/gradlew -p sdk test :sdk-android:assembleRelease :sdk-android:apiCheck`; `scripts/verify-contract.sh`; `scripts/check-repo-rules.sh`.
- A temporary public signature change makes `apiCheck` fail; after the revert it passes.

## Comments
