# 10. Whitespace set in the contract and fixtures

Status: ready-for-agent
Type: task
Blocked by: 02, 03
Execution: Not started

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
