# 38. Tracking plan review follow-ups

Status: resolved
Type: task
Blocked by: None
Execution: Done (merged in PR #99, 2026-10-15 UTC)

## Scope and completion criteria

Two review findings from PR #95 (issue 37) that were open at merge.

1. Minor: `tracking-plan/generate.mjs` `validatePlan` checks values before types. `kotlinPackage: ["a.b"]` passes the regular expression through string conversion and then `split` throws a `TypeError`; a property `type: ["string"]` passes `Object.hasOwn` and generation succeeds for an invalid plan. Require strings for both fields (and any other field that is used as a string) before the value checks, report them as plan errors, and add tests.
2. Trivial: `ShopViewModelTest` uses a fake client, so no test exercises `SdkSignalDockClient` and the generated wrappers. Add an adapter-boundary test that maps each `DemoEvent` through the SDK adapter and checks the event name and every property, including `is_simulated` on `purchase_completed`.

Verification:
- `node --test tracking-plan/generate.test.mjs`; `sdk/gradlew -p sdk test :demo:app:assembleDebug --no-daemon`; `scripts/verify-contract.sh`; `scripts/check-repo-rules.sh`.

## Comments

- 2026-10-15 (PR #99 review): ADR-0021 first said the adapter test uses only the public SDK API; it also reads the internal queue database. Fixed before merge.
