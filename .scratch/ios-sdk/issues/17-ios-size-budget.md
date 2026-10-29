# 17. iOS size budget gate

Status: claimed
Type: task
Blocked by: 16

## Scope and completion criteria

Spec D13 item 10, D15. ADR-0023.

1. A CI job fails when the SDK adds more than a limit to the consumer Release build. The limit comes from ticket 16 with headroom, and the reason for the number is written down.
2. `AGENTS.md` table row.

Verification:
- CI run link; a planted size increase fails the job (output).

## Verification (2026-10-29)

- `scripts/check-ios-size-budget.sh` (fresh ZIP of HEAD, Xcode 27.1 local), exit 0, 24 s:
  ```
  ipa_bytes: without SDK 49263, with SDK 202566
  delta ipa_bytes: 153303 bytes (budget 175000, ADR-0047 ticket 17)
  binary_bytes: without SDK 164560, with SDK 553024
  delta binary_bytes: 388464 bytes (budget 440000, ADR-0047 ticket 17)
  OK: within budget
  ```
  Same numbers as ticket 16.
- Planted increase: `scripts/check-ios-size-budget.sh <ZIP>` with a release ZIP whose `SignalDockSDK.swift` has `import Foundation` and a line in `initialize` that compares `ProcessInfo.processInfo.environment["PLANT"]` with an 80 KB random base64 string literal. Two lines of the failing output (the script exits 1):
  ```
  delta ipa_bytes: 215966 bytes (budget 175000, ADR-0047 ticket 17)
  FAIL: the SDK adds 40966 bytes (ipa_bytes) more than the budget. Remove the cause, or raise the budget on purpose (see the top of this script).
  delta binary_bytes: 470504 bytes (budget 440000, ADR-0047 ticket 17)
  FAIL: the SDK adds 30504 bytes (binary_bytes) more than the budget. Remove the cause, or raise the budget on purpose (see the top of this script).
  ```
- Method, metrics, budgets, reason: ADR-0047 "Amendment (2026-10-29, ticket 17, size budget)". Row in `AGENTS.md`.
- `actionlint` (without shellcheck: no output; with shellcheck: only the existing SC2010 and SC2155 warnings of other steps). `scripts/check-repo-rules.sh`: see the PR.
- Not run: CI (Xcode 26.6 numbers come from the first CI run of `ios-size-budget`; the budgets may need a change then, see the ADR amendment); a physical device.
