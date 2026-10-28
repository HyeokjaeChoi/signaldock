# 11. Tracking plan: Swift output

Status: claimed
Type: task
Blocked by: 08

## Scope and completion criteria

Spec D10. ADR-0021.

1. `tracking-plan/generate.mjs` writes a Swift file of typed event builders over the `track` API, from `plan.json` (and `manifest.json`), like the Kotlin output. `generate.test.mjs` covers it.
2. The generated file compiles in a test target of `sdk-ios`. The drift check in `scripts/verify-contract.sh` covers it.

Verification:
- `node --test tracking-plan/generate.test.mjs`; `scripts/verify-contract.sh`; iOS build.
