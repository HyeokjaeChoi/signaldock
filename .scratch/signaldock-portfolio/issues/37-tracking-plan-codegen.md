# 37. Optional tracking plan and code generation

Status: claimed
Type: task
Blocked by: None
Execution: Implemented on branch impl/tracking-plan, PR pending

## Scope and completion criteria

Spec Q152 to Q154: an optional tracking plan (event taxonomy) with code generation for Kotlin and TypeScript. No server-side validation. The SDK and its public API do not change. The decisions are in ADR-0021.

1. `tracking-plan/plan.json` describes the demo events (name, description, properties with type and required flag). `node tracking-plan/generate.mjs` validates the plan (snake_case names and keys, allowed types, no duplicates) and rejects an invalid plan with a clear error.
2. The generator writes typed Kotlin extension functions for `SignalDock`, TypeScript types and builders that return `{ name, properties }`, and a manifest. The outputs are committed in `tracking-plan/generated/`. `scripts/verify-contract.sh` step 7 checks that they match a fresh generation.
3. The demo app (`sdk/demo`) sends its events through the generated Kotlin functions. `compatibility/consumer-android` stays on plain `track()`. `sdk/sdk-android` has no change and `apiCheck` passes with the unchanged dump.
4. A test proves that the Kotlin and TypeScript outputs from the same plan agree on event names, property keys, and value types (`tracking-plan/generate.test.mjs`).
5. The integration guide (English and Korean), both READMEs, `CHANGELOG.md`, and `docs/design-index.md` are updated.

Verification:
- `scripts/check-repo-rules.sh`; `scripts/verify-contract.sh`; `sdk/gradlew -p sdk test :sdk-android:assembleRelease :sdk-android:apiCheck :demo:app:assembleDebug --no-daemon`; `node --test tracking-plan/generate.test.mjs`.

## Comments
