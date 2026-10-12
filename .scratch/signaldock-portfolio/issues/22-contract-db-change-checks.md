# 22. Contract and DB change checks (part of issue 07)

Status: ready-for-agent
Type: task
Blocked by: None
Execution: In review

## Scope and completion criteria

Spec section 7 rows "Contract changes" and "DB changes".

- Contract: regenerate and compile models, compare with the prior schema, and check prior-request and new-response fixture compatibility. Much of this runs in CI already (`scripts/verify-contract.sh`); confirm what runs and fill gaps.
- DB: Flyway migrations, jOOQ generation, and server queries on an empty DB and on a DB at the previous schema.

- Q125 (one request time budget, `statement_timeout`, shorter `lock_timeout`, multi-statement and commit/rollback boundaries): confirm each has a server test; add missing ones.

Done when each check runs and every Q125 item maps to a passing test, from a command (CI where cheap), with results recorded here.

## Comments

2026-10-12: Split from issue 07.

2026-10-12: Implemented on branch `impl/22-contract-db-change-checks` (no push, no PR yet). Decisions: ADR-0012 (`docs/adr/0012-contract-and-db-change-checks.md`).

Spec section 7 criteria to check:

| Criterion | Check | Runs in |
| --- | --- | --- |
| Contract: regenerate models | `scripts/verify-contract.sh` step 1 (`scripts/codegen.sh` into a temp dir, `diff -rq` with `generated/`) | CI `verify-contract` |
| Contract: compile models | step 3 `contract-check` Gradle build compiles `generated/kotlin`; step 4 `npm run typecheck` and `contract-check` compile the TypeScript models; `:app` compiles the same Kotlin DTOs in the server build | CI `verify-contract`, `server-test` |
| Contract: compare prior schema | step 2 `oasdiff diff --fail-on-diff` against `fixtures/contract/contract-v1.baseline.yaml` (Git baseline, ADR-0002 amendments) | CI `verify-contract` |
| Contract: prior-request fixture | New: `fixtures/compat/prior-request-v1.json` replayed by `PriorRequestFixtureTest` (ACCEPTED, then DUPLICATE on resend); also decoded by `RoundTripTest` | CI `server-test`, `verify-contract` |
| Contract: new-response fixture | New: `fixtures/compat/new-ack-added-fields.json` read by the prior generated models with `ignoreUnknownKeys`; unknown status and missing field still fail (`RoundTripTest`). Query responses: existing `query responses tolerate added fields...`. SDK side: `DeliveryTest` "compatible extra ACK fields are ignored" | CI `verify-contract`, `sdk-test` |
| Contract: round-trip fixtures | existing `fixtures/roundtrip/*` in `RoundTripTest` and `dashboard/src/contract-check.ts` | CI `verify-contract` |
| DB: Flyway migrations on an empty DB | `SchemaUpgradeTest` "empty database migrates to head..."; `verifyJooq` also migrates an empty DB | CI `server-test` |
| DB: jOOQ generation | `verifyJooq` (regenerate into a temp dir, compare file set and content) | CI `server-test` |
| DB: queries on a previous-schema DB | New: `SchemaUpgradeTest` "database upgraded one migration at a time..." (ADR-0012: migrate to each version with Flyway `target`, load `previous-data/V<n>.sql`, migrate to head, `validate`, run list/details/counts and ingestion on old rows) | CI `server-test` |

Q125 to tests (all in `server/persistence` `DatabaseTest` unless noted; new tests marked N):

| Q125 part | Test |
| --- | --- |
| One request time budget; no independent timeouts that add up | N `limits keep one request budget and a lock timeout shorter than the statement timeout` (invariants of `DatabaseLimits`); N `a multi-statement transaction stays inside the request budget and rolls back every statement` |
| Admission wait and 503 with Retry-After | `admission overload fails fast and releases the slot`; `IngestionIntegrationTest` `route returns retryable 503 ErrorResponse with Retry-After`; `QueryRoutesTest` `route returns retryable 503 with Retry-After when the database is saturated` |
| `statement_timeout` | `statement timeout rolls back and the connection is reusable without leaked settings`; N `each statement runs with the configured statement_timeout and a shorter lock_timeout` |
| Shorter `lock_timeout` and lock contention with the same eventId | N `each statement runs with the configured statement_timeout and a shorter lock_timeout`; `IngestionIntegrationTest` `lock timeout rolls back and a resend succeeds after lock release`; `concurrent inserts rely on unique constraint and store one row` |
| Multi-statement boundary (a statement limit is not a transaction limit) | N `a multi-statement transaction stays inside the request budget and rolls back every statement` |
| Commit/rollback boundaries | `statement timeout rolls back...` (rollback, pool reuse, no setting leak); N `cancelling the caller cancels the running statement, rolls back and frees the slot`; N `a cancel seen before commit rolls the transaction back`; `IngestionIntegrationTest` `lost response after commit becomes duplicate on resend` |

Commands and results (JDK from `scripts/setup-toolchain.sh`, Docker running):
- `(cd server && ./gradlew test verifyJooq --no-daemon)`: BUILD SUCCESSFUL. JUnit XML tests, failures, skipped: app 34/0/0, ingestion 5/0/0, persistence 19/0/0, query 12/0/0. `verifyJooq` ran and passed.
- `scripts/verify-contract.sh`: ALL CONTRACT CHECKS PASSED. `contract-check` 8 tests, 0 failures; dashboard 19 tests passed; TypeScript contract-check assertions passed.
- `coderabbit review --base main --deep --agent`: 2 minor findings, both in `DatabaseTest.kt`. Fixed: bound the cancel wait with `withTimeout`. Rejected: assert only a range for the effective timeouts. The request budget (about 4.2 s) is four times the statement limit, so the first statement uses the configured values exactly, and the test must prove that the configured values are applied (ADR-0004; architecture "DB time budgets and queries").

Limits:
- No second migration exists, so the previous-schema check loads V1 rows at V1 and re-checks them at head. It has not yet upgraded real data across a schema change; the loop does that once V2 and `previous-data/V2.sql` exist.
- A cancel that reaches PostgreSQL while a connection is idle between two statements is lost (the cancel test polls `pg_stat_activity` on a second connection until a backend is `active` on `pg_sleep(4)`, 5 s deadline, instead of a fixed 300 ms wait; updated 2026-10-12); the deadline task cancels again at the budget end.
- The commit-uncertain window (a cancel during `COMMIT`) has no deterministic test; the lost-response test covers the client-visible result.
- `verify-contract.sh` still runs only `gradlew help` for the server, so the server replay test runs in `server-test`.
- Not executed: CI. Nothing is pushed, so CI results do not exist yet.
