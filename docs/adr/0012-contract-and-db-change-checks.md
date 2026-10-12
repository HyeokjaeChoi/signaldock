---
status: accepted
---

# Contract and DB change checks

Issue 22 covers spec section 7 rows "Contract changes" and "DB changes". These decisions fill gaps in ADR-0002, ADR-0004, and the spec. They are implementer decisions that the user can change.

**Compatibility fixtures.** `fixtures/compat/` holds frozen files. `prior-request-v1.json` is a request in the shape the first SDK sends (contract 1, server-valid IDs). `PriorRequestFixtureTest` (`server/app`) replays it against the current server and expects ACCEPTED, then DUPLICATE on a resend. `new-ack-added-fields.json` is an ACK with extra fields. `RoundTripTest` (`contract-check`) reads it with the generated models and the SDK's `ignoreUnknownKeys` setting, and shows that an unknown status or a missing field still fails (Q68). When the contract changes, keep the old file and add a new one; never edit a frozen fixture. No earlier SDK has been published, so "prior" means the first form of contract 1 (spec section 9, item 4).

**"Previous schema" for the first release.** Only `V1__create_events.sql` exists, so no earlier schema version exists. The check does not wait for V2. `SchemaUpgradeTest` (`server/persistence`) builds the previous-schema case from the migration list:

1. Create an empty database (case a: migrate to head, then ingest and query).
2. For each pending migration version `n`, in order: `flyway migrate` with `target = n`, then load `src/test/resources/db/previous-data/V<n>.sql`, which holds rows as a real database at schema `n` would hold them.
3. Migrate to head, run `validate`, and run the server queries (list, details, counts) and ingestion (duplicate, conflict, new row) on the old rows.

With one migration, step 2 loads the V1 rows at V1 and step 3 re-checks them at head. When V2 is added, the same test loads V1 rows at V1 and migrates them to V2. The test fails if `V<n>.sql` is missing, so a migration author must also write the data that the next migration must carry. A seed file is never edited after a later migration exists.

jOOQ generation (`verifyJooq`) stays on an empty temporary database built from the migrations (Q91). It does not run on a previous schema, because generated sources describe the head schema only.

**Commands.** `cd server && ./gradlew test verifyJooq --no-daemon` runs the DB checks and the Q125 tests (CI job `server-test`). `scripts/verify-contract.sh` runs codegen drift, oasdiff, `contract-check`, and the TypeScript checks (CI job `verify-contract`). `verify-contract.sh` still evaluates the server build with `gradlew help` only, so the server replay test runs in `server-test`, not in `verify-contract`.

**Q125 tests.** The test names are listed in the issue 22 comment. They check the request budget and statement/lock limits against a real PostgreSQL, not benchmark values.

**Known limits.** (1) A cancel that reaches PostgreSQL while the connection is idle between two statements is lost; the deadline task cancels again at the budget end, so the transaction still ends inside the request budget. (2) The commit-uncertain window (a cancel during `COMMIT`) is not reproduced by a deterministic test; the lost-response test covers the client-visible result (resend returns DUPLICATE). (3) Only SDK-side ACK handling is tested by the SDK's own `DeliveryTest`, which runs in `sdk-test`, not in `verify-contract`.
