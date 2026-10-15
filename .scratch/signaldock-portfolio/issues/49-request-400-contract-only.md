# 49. A request-level 400 means a contract bug only

Status: claimed
Type: task
Blocked by: None
Execution: Branch `impl/request-400-contract-only` (PR open). ADR-0027, spec Q158.

## Scope and completion criteria

Spec Q158: for a correct SDK, a request-level 400 is a contract or configuration bug, and holding the queue is intended (ADR-0006). The server must not give a request-level 400 for the content of one event.

1. A lone surrogate escape (for example `\ud800`) in an event text field is a per-item rejection, and valid events of the same batch are stored.
2. A nested object or array as a property value, at any depth, is a per-item `INVALID_PROPERTIES` rejection. The parser stays safe for deep input (no recursion on nested property values). A null property value is also `INVALID_PROPERTIES`.
3. A drift test: the SDK test `WireCorpusTest` keeps `fixtures/sdk-wire/` equal to the real SDK output. The server test `SdkWireCorpusTest` parses those files with the real parser and rules and expects no request-level failure and no rejected item.
4. ADR-0027, `docs/design-index.md`, spec Q158.

Verification:
- `cd server && ./gradlew test verifyJooq --no-daemon`; `sdk/gradlew -p sdk :sdk-android:testDebugUnitTest --no-daemon`; `scripts/verify-contract.sh`; `scripts/check-repo-rules.sh`.

## Comments

- 2026-10-15 verification (run on this branch):
  - `cd server && ./gradlew test verifyJooq --no-daemon`: BUILD SUCCESSFUL, 96 tests, 0 failed (90 before; 6 new: 3 in `IngestValidationTest`, 1 in `IngestionIntegrationTest`, 2 in `SdkWireCorpusTest`).
  - New tests against the old `RequestParsing.kt` (restored from `main` for one run): `./gradlew :app:test --tests '*IngestValidationTest' --tests '*SdkWireCorpusTest' --tests '*IngestionIntegrationTest'` gave 26 tests, 3 failed: the lone surrogate test, the nested value test, and the integration test. `SdkWireCorpusTest` passes on the old parser on purpose: the SDK cannot make those inputs, and the test guards the future.
  - `sdk/gradlew -p sdk :sdk-android:testDebugUnitTest --no-daemon`: BUILD SUCCESSFUL, 119 tests in 11 classes, 0 failed, including `WireCorpusTest` (corpus written once with `UPDATE_SDK_WIRE=1`, then compared without it).
  - `scripts/verify-contract.sh`: ALL CONTRACT CHECKS PASSED. `scripts/check-repo-rules.sh`: exit 0.
