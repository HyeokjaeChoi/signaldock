# 10. Whitespace set in the contract and fixtures

Status: ready-for-agent
Type: task
Blocked by: 02, 03
Execution: Done (merged in PR #26, 2026-10-11)

## Scope and completion criteria

Spec Q64 says to fix the whitespace character set for event names and property keys in the contract and fixtures. Today the set is only in code and ADRs:

- server: `IngestValidation.kt` (ADR-0004)
- SDK: `Validation.kt` `isNameWhitespace` (ADR-0003, item 4)

Both use the same Unicode White_Space list: U+0009..U+000D, U+0020, U+0085, U+00A0, U+1680, U+2000..U+200A, U+2028, U+2029, U+202F, U+205F, U+3000.

Done when:

- `openapi/contract-v1.yaml` states the set in the description of the name and key fields.
- `fixtures/` has cases for each member of the set (rejected when leading, trailing, or alone) and for near misses that are accepted (U+001C, U+200B, U+FEFF, U+180E).
- `contract-check` runs these fixtures against the SDK and server validation, so a change on one side fails the check.

## Comments

2026-10-11: Created while aligning issue 02 and issue 03. The SDK used `Char.isWhitespace()`; it now uses the server's explicit list.

2026-10-11: Implemented.
- `openapi/contract-v1.yaml`: descriptions of `EventIngest.name` and `EventIngest.properties` (keys) state the set. Description text only. `generated/` regenerated via `scripts/codegen.sh` (only doc comments in `EventIngest.kt` and `EventIngest.ts` changed). `fixtures/contract/contract-v1.baseline.yaml` updated on purpose.
- `oasdiff breaking <baseline from main> openapi/contract-v1.yaml` output: `No breaking changes to report, but the specs are different.` (exit 0). `oasdiff diff` shows only the two description changes.
- `fixtures/validation/whitespace-set.json`: 25 members and 4 near misses (U+001C, U+200B, U+FEFF, U+180E).
- SDK: `TrackTest` now reads the fixture (no inline list). Server: new `IngestRulesTest` case reads the same file. Both check each member as leading, trailing, and alone, in the name and in a property key, and check near misses are accepted. Production code is unchanged; no mismatch found.
- Placement: tests live in the SDK and `:ingestion` test suites, not in `contract-check`. `contract-check` compiles only the generated DTOs and has no dependency on the SDK or server validation code. A fixture change or a rule change on either side fails that side's test, and `verify-contract.sh` does not run SDK/server tests (CI runs those builds).
- Results: `sdk/gradlew -p sdk :sdk-android:testDebugUnitTest --no-daemon` BUILD SUCCESSFUL; `(cd server && ./gradlew test verifyJooq --no-daemon)` BUILD SUCCESSFUL; `scripts/verify-contract.sh` ALL CONTRACT CHECKS PASSED.

- `.tools/oasdiff changelog <baseline from main> openapi/contract-v1.yaml` output (ADR-0002 amendment \"intentional baseline update\"):

```text
No changes to report, but the specs are different.
Run 'oasdiff diff' to see structural differences.
```

2026-10-12: Record reconciled. Merged into main in PR #26 on 2026-10-11. Earlier branch and push notes above describe the state before that merge.
