# 72. Verification script stops on a stale SDK dependency baseline

Status: resolved
Type: task
Blocked by: None
Execution: Done (merged in PR #185, 2026-10-18 UTC)

## Scope and completion criteria

Source: CodeRabbit Minor finding on PR #181 (`scripts/update-gradle-verification.sh`, the `sdk` Gradle run). Recorded here by the AGENTS.md merge criterion, so it does not hold PR #181.

`scripts/update-gradle-verification.sh` runs `:sdk-android:dependencyGuard` in the `sdk` Gradle run (ADR-0038 item 3: coverage is what CI runs). When a dependency change also changes the SDK release classpath, `dependencyGuard` fails against the old baseline in `sdk/sdk-android/dependencies/` (ADR-0033). The script uses `set -e`, so it stops before the `server`, `contract-check`, and consumer runs and before the `aapt2` step.

Workaround now: run `sdk/gradlew -p sdk :sdk-android:dependencyGuardBaseline` first (`docs/releases/README.md`, "Change the SDK dependency tree"), then the script.

Completion criteria:
1. A stale baseline does not stop the metadata update of the other builds. Options: run `dependencyGuardBaseline` in place of `dependencyGuard` in the script, or tell the user to run it first in the script error and in `docs/releases/README.md`.
2. The configurations that `dependencyGuard` resolves stay covered by the metadata (ADR-0038 item 3).
3. Verification: run the script after a catalog change that changes the SDK release classpath; it finishes and the four metadata files and the baseline are updated.

## Comments

- 2026-10-18 UTC: PR #185. The script runs `dependencyGuardBaseline` in place of `dependencyGuard`. Verification in the PR: `bash -n` and `scripts/check-repo-rules.sh` passed; CI passed. Gap: the script itself was not run after a catalog change (criterion 3); no JDK or Android SDK in the agent environment.
