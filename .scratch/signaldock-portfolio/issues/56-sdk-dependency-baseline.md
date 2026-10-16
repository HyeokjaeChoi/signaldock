# 56. Commit the SDK published dependency baseline

Status: resolved
Type: task
Blocked by: None
Execution: Done (merged in PR #147, 2026-10-16 16:49:52 UTC)

## Scope and completion criteria

The dependency tree that the SDK puts into a host app is committed as a baseline. CI fails when the tree changes and the baseline does not (like `android/nowinandroid`, which uses the Dropbox dependency-guard plugin). A transitive change from any update, Renovate PRs included, shows up in the diff. Spec Q162 is cited as "in the CodeQL PR (issue 54)"; the spec is not edited.

1. Use the plugin or a small in-repo task. Record the choice and the rejected option in ADR-0033.
2. Baseline `releaseRuntimeClasspath` and `releaseCompileClasspath` of `:sdk-android` under `sdk/sdk-android/dependencies/`.
3. `scripts/verify-contract.sh` runs the check. The update command and the CHANGELOG rule are in `docs/releases/README.md`.
4. Show that the check passes, and fails after a dependency version change.

Verification: `sdk/gradlew -p sdk :sdk-android:dependencyGuard`; `scripts/check-repo-rules.sh`; `coderabbit review --base main --deep --agent`.

## Comments

- 2026-10-16 UTC: PR #147 merged 16:49:52Z (ADR-0033).
- 2026-10-16 UTC: open follow-up: the runtime `kotlin-stdlib` resolves to 2.3.20, while the POM states 2.2.21 (spec Q132, ADR-0009 decision 4). Tracked in issue 57.
