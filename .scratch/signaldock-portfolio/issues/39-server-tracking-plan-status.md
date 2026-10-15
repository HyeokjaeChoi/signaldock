# 39. Server marks events with a tracking plan status

Status: claimed
Type: task
Blocked by: None
Execution: Implemented on branch impl/server-plan-status, PR pending

## Scope and completion criteria

Spec Q155: the server checks stored events against the tracking plan and marks them. It never rejects or drops an event for plan reasons. The decisions are in ADR-0022.

1. With a plan configured, each new event gets a plan status: `planned`, `unplanned_event`, or `invalid_properties`. With no plan, or for an event stored before this change, there is no status ("not checked"). A duplicate keeps the status of the first insert.
2. The plan comes from the file in `SIGNALDOCK_TRACKING_PLAN` (Compose mounts `tracking-plan/plan.json`). A file that is not usable stops the server at start with a clear error.
3. The contract change is additive: an optional `planStatus` on received events, a `planStatus` filter on the list and the other counts, and `GET /v1/event-counts/by-plan-status`. `oasdiff breaking` reports nothing. `generated/`, the baseline, the fixtures, and the round-trip tests (Kotlin and TypeScript) are updated.
4. The Dashboard shows a status badge in the list and the details, a status filter, and a violations KPI for the shown period, in English and Korean, with no new dependency.
5. The SDK, the ingest ACK, and `sdk/sdk-android` (and its API dump) do not change.
6. Docs: both READMEs, the integration guide (English and Korean), `CHANGELOG.md`, `docs/design-index.md`, `architecture.md`, spec Q155, ADR-0022 (and a note in ADR-0021).

Verification:
- `server/gradlew -p server test verifyJooq --no-daemon`; `scripts/verify-contract.sh`; `scripts/check-repo-rules.sh`; `cd dashboard && npm run typecheck && npm test`; `sdk/gradlew -p sdk :sdk-android:apiCheck --no-daemon`; an end-to-end run with Docker Compose in the scratch project `sdplan`.

## Comments

- 2026-10-15 contract check (ADR-0002 amendment): `oasdiff breaking fixtures/contract/contract-v1.baseline.yaml openapi/contract-v1.yaml`, with the baseline from `main` as the base, printed "No breaking changes to report, but the specs are different." (exit 0). `oasdiff changelog` printed `6 changes: 0 error, 0 warning, 6 info`: `new-optional-request-parameter` `planStatus` on `GET /v1/events`, `/v1/event-counts/by-name`, and `/v1/event-counts/by-time`; `endpoint-added` `GET /v1/event-counts/by-plan-status`; `response-optional-property-added` `planStatus` on `GET /v1/events` items and on `GET /v1/events/{eventId}`. The baseline `fixtures/contract/contract-v1.baseline.yaml` is updated on purpose in this change. The contract version stays 1 (compatible additions, Q65).
