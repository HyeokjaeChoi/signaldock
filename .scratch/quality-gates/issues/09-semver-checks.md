# 09. Semver checks of the public API dumps

Status: claimed
Type: task
Execution: In progress

## Scope and completion criteria

Spec gates A7 and I3. ADR-0049 decisions 2, 3.

1. `scripts/check-api-semver.sh android|ios`: fails when the dump (`sdk/sdk-android/api/sdk-android.api`, `sdk-ios/api/SignalDock.swiftinterface`) has a removed or changed line against the last `v*` (`ios-v*`) tag and the version (`sdkVersion`, `SignalDockSDK.version`) has the same MAJOR (before 1.0: same MINOR).
2. Runs in `scripts/verify-contract.sh` step 6 (A7) and step 8 (I3, outside the Xcode check: it reads committed files only). Works in the depth-1 CI checkout (the tag is found with `git ls-remote` and fetched) and in pre-push.
3. `scripts/check-api-semver.test.sh` proves pass and fail on bad samples; it runs in `verify-contract.sh` after the bundle budget test.
4. Evidence in the PR description: both checks fail on a bad sample in a depth-1 clone and pass on clean main.
5. `AGENTS.md` row and a dated ADR-0049 amendment.
