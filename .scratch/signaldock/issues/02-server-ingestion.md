# 02. Durable server receipt and duplicate handling

Status: resolved
Type: task
Blocked by: 01
Execution: Done (merged in PR #12, 2026-10-11)

## Scope and completion criteria

Generate Flyway and jOOQ outputs; implement eventId uniqueness, content comparison, partial rejection, and ACK after commit; verify overload, timeout, rollback, and response loss.

Preserve the applicable contracts in the [agreed spec](../spec.md) and [final review](../final-review.md). Distinguish implementation, execution, and failure evidence. Assess completion after recording the outputs and actual verification.

## Comments

2026-10-09: Created after the full Q127 agreement.

2026-10-11: Implemented the Ktor ingestion route, strict contract and semantic validation, collection-key check, partial per-event rejection, PostgreSQL transaction handling, `eventId` uniqueness, exact duplicate-content comparison, and post-commit ACK behavior. Added Flyway `V1` with typed columns and JSONB, a receivedAt/eventId cursor index, 7 jOOQ Java outputs prepared for Git tracking, clean temporary-schema generation and drift verification, and Testcontainers coverage for concurrent duplicates, conflicts, overload admission, lock and statement timeouts, rollback, connection reuse, and response loss after commit. Added ADR-0004 for the Java 17-compatible jOOQ line, normal local timeout defaults, collection-key header, invalid repeated-ID groups, and cancellation behavior.

Verification commands and results:

- `scripts/setup-toolchain.sh` — passed; installed checksum-verified Temurin 17.0.20.1+1, oasdiff 1.33.0, and openapi-generator-cli 7.26.0 in `.tools/`.
- `cd server && export JAVA_HOME="$PWD/../.tools/jdk-17.0.20.1+1" && export GRADLE_USER_HOME="$PWD/../.tools/gradle-home" && ./gradlew test verifyJooq --no-daemon` — passed; 13 server tests passed, Flyway applied `V1` to PostgreSQL 17.6, and clean jOOQ regeneration matched all 7 tracked generated files.
- `export JAVA_HOME="$PWD/.tools/jdk-17.0.20.1+1" && export GRADLE_USER_HOME="$PWD/.tools/gradle-home" && export npm_config_cache="$PWD/.tools/npm-cache" && scripts/verify-contract.sh` — passed; ended with `ALL CONTRACT CHECKS PASSED`.
- `git switch -c impl/02-server-ingestion` — failed before changing Git state: the sandbox returned `Operation not permitted` while creating the common repository ref lock.
- `git add .scratch/signaldock/issues/02-server-ingestion.md docs/adr/0004-server-runtime-and-receipt-boundaries.md gradle/libs.versions.toml server` — failed before staging: the sandbox returned `Operation not permitted` while creating the worktree index lock.
- Commit — not run because branch creation could not write the common Git metadata. Push and PR creation were not run as requested.

2026-10-11: Verified and fixed two ingestion validation review findings.

- `cd server && JAVA_HOME=$PWD/../.tools/jdk-17.0.20.1+1 GRADLE_USER_HOME=$PWD/../.tools/gradle-home ./gradlew test --tests local.signaldock.server.IngestValidationTest --no-daemon` before the fix — failed as expected; the 256 KiB nested-array case produced `StackOverflowError`, and the `+300000` year case passed validation instead of returning `INVALID_OCCURRED_AT`.
- The strict JSON scanner now rejects a container deeper than depth 8 as `MALFORMED_REQUEST`. The `occurredAt` validator now rejects an `Instant` whose UTC year is outside `1..9999` as per-item `INVALID_OCCURRED_AT`.
- `cd server && JAVA_HOME=$PWD/../.tools/jdk-17.0.20.1+1 GRADLE_USER_HOME=$PWD/../.tools/gradle-home ./gradlew test --tests local.signaldock.server.IngestValidationTest --no-daemon` after the fix — passed; all 7 validation tests passed.
- `cd server && JAVA_HOME=$PWD/../.tools/jdk-17.0.20.1+1 GRADLE_USER_HOME=$PWD/../.tools/gradle-home ./gradlew test verifyJooq --no-daemon` — passed on the final code; all 15 server tests passed, Flyway applied `V1` to PostgreSQL 17.6, and `verifyJooq` regenerated the expected 7 files without drift.
- Commit — not run as requested.

2026-10-11: Fixed the adversarial review findings for request-level contract-version rejection and documented HTTP boundaries.

- Added raw per-item `contractVersion` inspection before generated DTO decoding. A missing, unsupported, or request-header-mismatched item version now raises a request-level `RequestFailure`, so no sibling event is stored. The mixed contract-1/contract-2 HTTP integration test failed before the fix with `expected: <400 Bad Request> but was: <200 OK>`, then passed with HTTP 400 and zero rows after the fix.
- Added the Bearer collection-key security scheme, the identity-only `Content-Encoding` boundary, and 401, 415, and retryable 503 `ErrorResponse` responses to `openapi/contract-v1.yaml`. The 503 response documents a positive `Retry-After` header. Added server route tests for 401, unsupported Content-Type and Content-Encoding as 415, and admission overload as 503 with `Retry-After: 1`.
- Ran `scripts/codegen.sh`; generated Kotlin transport models remain `internal` through `nonPublicApi=true`. Copied `openapi/contract-v1.yaml` to `fixtures/contract/contract-v1.baseline.yaml` on purpose because this is an intentional contract change.
- Removed duplicate `coroutines` and `kotlinx-coroutines-test` entries left in `gradle/libs.versions.toml` by the rebase. Before this prerequisite fix, Gradle stopped while parsing the version catalog and did not run tests.
- `JAVA_HOME=$PWD/.tools/jdk-17.0.20.1+1 GRADLE_USER_HOME=$PWD/.tools/gradle-home npm_config_cache=$PWD/.tools/npm-cache sh -c '(cd server && ./gradlew test verifyJooq --no-daemon)'` — passed; all 19 server tests passed, Flyway applied `V1` to PostgreSQL 17.6, and clean jOOQ regeneration matched all 7 tracked files.
- `JAVA_HOME=$PWD/.tools/jdk-17.0.20.1+1 GRADLE_USER_HOME=$PWD/.tools/gradle-home npm_config_cache=$PWD/.tools/npm-cache scripts/verify-contract.sh` — passed; codegen drift, the intentionally updated OpenAPI baseline, 5 Kotlin round-trip tests, TypeScript type/contract checks, and independent SDK/server Gradle builds passed; ended with `ALL CONTRACT CHECKS PASSED`.
- ADR-0002 and ADR-0004 were not changed because this work implements and exposes existing contract and runtime decisions; it does not add a new contract rule or change a runtime decision.
- Commit — not run as requested.

### Contract change against the previous baseline

2026-10-11: Compared the current contract with the baseline from `origin/main`. The checked-in baseline was not reverted.

Exact commands:

```sh
git show origin/main:fixtures/contract/contract-v1.baseline.yaml > /tmp/prev-baseline.yaml
.tools/oasdiff breaking /tmp/prev-baseline.yaml openapi/contract-v1.yaml
.tools/oasdiff changelog /tmp/prev-baseline.yaml openapi/contract-v1.yaml
```

`breaking` output:

```text
No breaking changes to report, but the specs are different.
Run 'oasdiff diff' to see structural differences.
```

`changelog` output:

```text
6 changes: 0 error, 0 warning, 6 info
info	[api-security-component-added]
	in components/securitySchemes
		the component security scheme `collectionKey` was added

info	[api-security-added] at openapi/contract-v1.yaml
	in API POST /v1/events:batch
		the endpoint scheme security `collectionKey` was added to the API

info	[new-optional-request-parameter] at openapi/contract-v1.yaml
	in API POST /v1/events:batch
		added the new optional `header` request parameter `content-encoding`

info	[response-non-success-status-added] at openapi/contract-v1.yaml
	in API POST /v1/events:batch
		added the non-success response with the status `401`

info	[response-non-success-status-added] at openapi/contract-v1.yaml
	in API POST /v1/events:batch
		added the non-success response with the status `415`

info	[response-non-success-status-added] at openapi/contract-v1.yaml
	in API POST /v1/events:batch
		added the non-success response with the status `503`


```

2026-10-11: Rejected property decimals that cannot round-trip through a finite IEEE `Double`, or exceed PostgreSQL `numeric` integer/fraction digit limits. Before the fix, the focused validation test failed because `1e-20000` was accepted. After the fix, the focused validation and PostgreSQL integration tests passed; the integration test stored the valid sibling and returned `INVALID_PROPERTIES` for the bad item.

Final verification commands and results:

- `(cd server && JAVA_HOME=$PWD/../.tools/jdk-17.0.20.1+1 GRADLE_USER_HOME=$PWD/../.tools/gradle-home ./gradlew test verifyJooq --no-daemon)` — passed; all 20 server tests passed, Flyway applied `V1` to PostgreSQL 17.6, and jOOQ 3.19.39 regenerated all 7 expected files without drift.
- `JAVA_HOME=$PWD/.tools/jdk-17.0.20.1+1 GRADLE_USER_HOME=$PWD/.tools/gradle-home npm_config_cache=$PWD/.tools/npm-cache scripts/verify-contract.sh` — passed; codegen drift, baseline, 5 Kotlin fixture tests, TypeScript type/contract checks, and independent SDK/server builds passed; ended with `ALL CONTRACT CHECKS PASSED`.
- Commit — not run as requested.

2026-10-12: Record reconciled. Merged into main in PR #12 on 2026-10-11. Earlier branch and push notes above describe the state before that merge.
