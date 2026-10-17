# 61. SDK client reports (dropped event counts)

Status: claimed
Type: task
Blocked by: None
Execution: Branch `impl/sdk-client-reports`, PR pending (ADR-0036).

## Scope and completion criteria

Spec Q164. The decisions are in ADR-0036.

1. The SDK counts dropped events by reason (`queue_full`, `storage_error`, `invalid_event`, `event_too_large`, `server_rejected`), keeps the counters in Room (schema 3), and sends them to `POST /v1/client-reports` at most once per send cycle, after the event batch. Opt-out is not a drop. The public API does not change.
2. 2xx: the sent counts are subtracted. 404 or another permanent 4xx: the report is discarded, no retry. Transient failure: the counters stay for the next cycle. The counters stop at 1,000,000.
3. The server validates the report (unknown fields, known reasons, counts 1 to 1,000,000, one entry per reason, body at most 4 KiB), stores one row per entry (migration `V3__create_client_reports.sql`), and answers `GET /v1/client-report-counts` for the Dashboard with the query key.
4. The Dashboard shows the dropped counts by SDK version and reason, in English and Korean, with no new dependency.
5. The contract change is additive. `oasdiff breaking` reports nothing. `generated/`, the baseline, and the round-trip fixtures are updated.
6. Docs: both READMEs if they list endpoints, the integration guide (English and Korean), `CHANGELOG.md`, `docs/design-index.md`, `architecture.md`, `docs/engineering/` rows that changed, spec Q164, ADR-0036.

Verification:
- `sdk/gradlew -p sdk :sdk-android:testDebugUnitTest :sdk-android:apiCheck --no-daemon`; `server/gradlew -p server test verifyJooq --no-daemon`; `scripts/verify-contract.sh`; `scripts/check-repo-rules.sh`; `cd dashboard && npm run typecheck && npm test`.

## Comments

- 2026-10-17 contract check (ADR-0002 amendment): `oasdiff breaking fixtures/contract/contract-v1.baseline.yaml openapi/contract-v1.yaml`, with the baseline from `main` as the base, printed "No breaking changes to report, but the specs are different." (exit 0). `oasdiff changelog` printed `2 changes: 0 error, 0 warning, 2 info`: `endpoint-added` `GET /v1/client-report-counts` and `endpoint-added` `POST /v1/client-reports`. The baseline is updated on purpose. Contract version stays 1.
- 2026-10-17 verification (run on this branch):
  - `sdk/gradlew -p sdk :sdk-android:testDebugUnitTest :sdk-android:apiCheck :sdk-android:dependencyGuard --no-daemon`: BUILD SUCCESSFUL (ClientReportTest 17 tests; public API dump and dependency baseline unchanged).
  - `server/gradlew -p server cleanTest test verifyJooq --no-daemon`: BUILD SUCCESSFUL, 128 tests, 0 failed (real PostgreSQL through Testcontainers).
  - `cd dashboard && npm run typecheck && npm test && npm run contract-check && npm run check-bundle-key`: pass, 174 tests.
  - `scripts/verify-contract.sh`: ALL CONTRACT CHECKS PASSED.
