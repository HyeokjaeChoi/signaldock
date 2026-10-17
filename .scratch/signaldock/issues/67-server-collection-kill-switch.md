# 67. Server-side collection kill switch

Status: claimed
Type: task
Blocked by: None
Execution: Branch `feat/server-collection-kill-switch`, PR open (not merged)

## Scope and completion criteria

Spec Q165, option A. The decisions are in ADR-0037. No SDK change, no wire or contract change.

1. The server reads `SIGNALDOCK_BLOCKED_EVENTS` and `SIGNALDOCK_BLOCKED_SDK_VERSIONS` (comma lists, exact match) at start. A blocked valid event gets a per-item `rejected` ACK with the code `COLLECTION_BLOCKED`. Other events of the batch are stored.
2. `SIGNALDOCK_INGEST_SHED=true` makes the two ingest routes answer 503 with `Retry-After` (`SIGNALDOCK_INGEST_SHED_RETRY_AFTER_SECONDS`, default 1, range 1 to 86400), after the contract and key checks. Nothing is stored.
3. With nothing set, nothing is blocked and nothing is shed. A malformed value stops the start.
4. `.env.example` and `docker-compose.yml` pass the variables.
5. Tests: blocked name, blocked version, shed 503 with `Retry-After`, nothing blocked by default, env parsing.
6. Docs: ADR-0037, spec Q165, README (English and Korean), integration guide (English and Korean), engineering docs, `docs/design-index.md`, `CHANGELOG.md`.

Verification:
- `server/gradlew -p server cleanTest test verifyJooq --no-daemon`; `scripts/verify-contract.sh`; `scripts/check-repo-rules.sh`.

## Comments

- 2026-10-17 verification (run on this branch):
  - `server/gradlew -p server cleanTest test verifyJooq --no-daemon`: BUILD SUCCESSFUL, 135 tests, 0 failed (CollectionControlTest 7 tests, real PostgreSQL through Testcontainers).
  - `scripts/verify-contract.sh`: ALL CONTRACT CHECKS PASSED. `scripts/check-repo-rules.sh`: exit 0.
  - `docker compose config` (with `.env.example` copied to `.env`) shows the four new server variables. Gap: the stack was not run.
  - Contract: `ItemAck.code` is a free string, so the new value `COLLECTION_BLOCKED` changes no schema. `openapi/` and `fixtures/` are untouched.
