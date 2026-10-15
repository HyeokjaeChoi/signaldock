# 40. CI device tests and APK size budget

Status: resolved
Type: task
Blocked by: None
Execution: Done (merged in PR #106, 2026-10-15 UTC)

## Scope and completion criteria

The decisions are in ADR-0023.

1. A CI job (`device-test`) runs `:sdk-android:connectedDebugAndroidTest` (the 26 device tests) on an API 34 `google_apis` x86_64 emulator with `reactivecircus/android-emulator-runner`, an AVD snapshot cache, and `timeout-minutes: 30`. The tests need no server and no root (MockWebServer on the device), so none are excluded.
2. A CI job (`size-budget`, `scripts/check-size-budget.sh`) builds the R8 consumer app with and without the SDK (`-Psignaldock.sdk=false`) and fails when the APK delta is above 700,000 bytes. It prints the delta. The budget is one constant in the script.
3. Docs: ADR-0023, `docs/design-index.md`, `CHANGELOG.md`, both READMEs (Scope and verification).

Verification:
- `scripts/check-size-budget.sh`; `scripts/check-repo-rules.sh`; `scripts/verify-contract.sh`; YAML parse of `.github/workflows/repo-rules.yml`.
- Only CI can show that the emulator job passes on API 34 and that the Linux delta is inside the budget (ADR-0023 limits).

## Comments

- 2026-10-15: `scripts/check-size-budget.sh` locally: without SDK 21,606 bytes, with SDK 638,683 bytes, delta 617,077 bytes, budget 700,000, OK.
- 2026-10-15: local `connectedDebugAndroidTest` on the running API 37 emulator failed in the environment (app data directory missing after install); see ADR-0023 limits.
