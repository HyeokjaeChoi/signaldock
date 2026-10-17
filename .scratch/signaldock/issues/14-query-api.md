# 14. Receipt query API (part of issue 05)

Status: resolved
Type: task
Blocked by: None
Execution: Done (merged in PR #32, 2026-10-12)

## Scope and completion criteria

Add the Dashboard query API to `openapi/contract-v1.yaml` and implement it in a new server module `:query` (ADR-0005, section "Server: layered Gradle modules"). Spec section 6 "Dashboard": received-event list and details, counts by type and time, sort, period filter, aggregation by receivedAt, and occurredAt in details. ADR-0002 Q107: the contract version header is required. Follow `.scratch/signaldock/dashboard-verification-defaults.md` and the architecture section "DB time budgets and queries" (no aggregate tables or counters).

Done when server tests, `scripts/verify-contract.sh`, and the oasdiff record (ADR-0002 amendment "intentional baseline update") are recorded here.

## Comments

2026-10-12: Split from issue 05.

2026-10-12: Implemented on `impl/14-query-api`, rebased on main 60a8fc9.

Built (design sources: spec 6 "Dashboard", 8 B Q70 and Q80-Q83, 11 Q107 and Q109, 12; `dashboard-verification-defaults.md`; architecture "DB time budgets and queries" and "Contracts, generated sources, and JSON"; ADR-0002 including Q107 and "intentional baseline update"; ADR-0004; ADR-0005 "Server: layered Gradle modules"):
- Contract: `GET /v1/events` (list, `sort`, `limit` 1-100 default 50, opaque `cursor`, period `from`/`to` on receivedAt, exact `name`/`installationId`/`eventId`), `GET /v1/events/{eventId}` (details with `occurredAt`), `GET /v1/event-counts/by-name`, `GET /v1/event-counts/by-time` (hour/day, empty buckets filled, at most 720). All require `SignalDock-Contract-Version` (Q107) and the Bearer collection key; errors use `ErrorResponse`. Request parameters are strict; response schemas have no `additionalProperties: false`. New shared `PropertyValue` schema, so the existing `EventIngestPropertiesValue` name is unchanged. `info.title` is now "SignalDock API".
- Decisions recorded in new ADR-0007 (auth by collection key, endpoint split, period and timezone rules, cursor, no new index, `:query` depends on `:ingestion` for `PropertyValue`). ADR-0005 module table and `docs/design-index.md` updated.
- `:query` (new, pure Kotlin): parameter parsing and bounds, 30-calendar-date period check in the project timezone, cursor, bucket rules. `:persistence` `QueryStore`: jOOQ keyset SQL on `events_received_cursor_idx`, `SET TRANSACTION READ ONLY`, same statement and lock limits as ingestion; COUNT / GROUP BY at query time, no aggregate tables or counters. `:app` `QueryRoutes.kt`: routes and DTO mapping; `RequestGuards.kt` shares the header and key checks with ingestion. Project timezone comes from `SIGNALDOCK_TIME_ZONE` (default `Asia/Seoul`, region IDs only).
- No Flyway migration. `QueryStoreTest` runs `EXPLAIN` on 20,000 rows after `ANALYZE`: the first page, a keyset page, and the count by name over one day all use `events_received_cursor_idx`, no `Seq Scan`.
- Fixtures: `fixtures/roundtrip/event-receipt-page.json`, `event-receipt-detail.json`, `event-name-counts.json`, `event-time-counts.json`, with round-trip and tolerance assertions in `contract-check` `RoundTripTest`.
- Baseline `fixtures/contract/contract-v1.baseline.yaml` updated on purpose in this branch. `generated/` regenerated with `scripts/codegen.sh` (the title change also touches comments in existing generated files).

oasdiff (v1.33.0), base = `git show main:fixtures/contract/contract-v1.baseline.yaml`, revision = `openapi/contract-v1.yaml`:

```
$ .tools/oasdiff breaking base.yaml openapi/contract-v1.yaml
No breaking changes to report, but the specs are different.
Run 'oasdiff diff' to see structural differences.
(exit 0)

$ .tools/oasdiff changelog base.yaml openapi/contract-v1.yaml
4 changes: 0 error, 0 warning, 4 info
info	[endpoint-added] at openapi/contract-v1.yaml
	in API GET /v1/event-counts/by-name
		endpoint added
info	[endpoint-added] at openapi/contract-v1.yaml
	in API GET /v1/event-counts/by-time
		endpoint added
info	[endpoint-added] at openapi/contract-v1.yaml
	in API GET /v1/events
		endpoint added
info	[endpoint-added] at openapi/contract-v1.yaml
	in API GET /v1/events/{eventId}
		endpoint added
(exit 0)
```

Non-breaking, so contract version 1 stays.

Commands and results (JDK 21.0.12.1+1 from `scripts/setup-toolchain.sh`, Docker running):
- `(cd server && ./gradlew test verifyJooq --no-daemon)`: BUILD SUCCESSFUL. Tests per module (from the JUnit XML): `:app` 28 (incl. 9 new in `QueryRoutesTest`), `:ingestion` 5, `:persistence` 11 (8 new in `QueryStoreTest`), `:query` 12 (new). 0 failures, 0 errors, 0 skipped. `verifyJooq` passed; no migration changed.
- `(cd server && ./gradlew :query:dependencies --configuration compileClasspath --no-daemon)`: only `project :ingestion`, `org.jetbrains.kotlin:kotlin-stdlib:2.4.21`, `org.jetbrains:annotations:13.0`. No Ktor, jOOQ, Flyway, JDBC, or serialization.
- `scripts/verify-contract.sh`: exit 0, "ALL CONTRACT CHECKS PASSED" (codegen drift, oasdiff baseline, contract-check 7 tests, TypeScript check, independent builds).

2026-10-12: Separate query key (spec Q128 to Q131, ADR-0007 "Authentication"). Supersedes the "Bearer collection key" statements above.

Changed:
- Contract: new `components.securitySchemes.queryKey`; `security: [queryKey]` on the four query operations; `collectionKey` stays on ingestion. `ErrorResponse.code` examples list `COLLECTION_KEY_INVALID` and `QUERY_KEY_INVALID`; the shared `Unauthorized` response now describes the query key. `scripts/codegen.sh` rerun (only `ErrorResponse` changed). Baseline `fixtures/contract/contract-v1.baseline.yaml` updated on purpose.
- Server `:app`: `RequestGuards.kt` checks the key by role (`requireCollectionKey` gives `COLLECTION_KEY_INVALID`, `requireQueryKey` gives `QUERY_KEY_INVALID`, both 401). `queryModule` takes the query key. `resolveKeys` in `Application.kt` reads `SIGNALDOCK_QUERY_KEY`: the server does not start if it is missing, blank, or equal to `SIGNALDOCK_COLLECTION_KEY`; errors name only variables.
- CodeRabbit Minor on PR #32: `GET /v1/events/{eventId}` now rejects any query parameter (unknown or repeated) with 400 `INVALID_PARAMETER` (`parseDetailRequest` in `:query`).
- Tests: `QueryRoutesTest` (query key 200; no Authorization, wrong, collection key, bad scheme all 401 `QUERY_KEY_INVALID`; details reject parameters), `IngestionIntegrationTest` (query key on ingestion gives 401 `COLLECTION_KEY_INVALID`), new `KeyConfigTest` (missing, blank, equal keys).
- Docs: spec Q128 to Q131 and sections 1 and 5, ADR-0007, `architecture.md` key lines, issue 15 scope (nginx and Vite proxy, `127.0.0.1`, `.env.example`). No README or issue 16 text listed server env vars.

oasdiff (v1.33.0), base = `git show main:fixtures/contract/contract-v1.baseline.yaml`, revision = `openapi/contract-v1.yaml`:

```
$ .tools/oasdiff breaking base.yaml openapi/contract-v1.yaml
No breaking changes to report, but the specs are different.
Run 'oasdiff diff' to see structural differences.
(exit 0)

$ .tools/oasdiff changelog base.yaml openapi/contract-v1.yaml
5 changes: 0 error, 0 warning, 5 info
info	[api-security-component-added]  components/securitySchemes: the component security scheme `queryKey` was added
info	[endpoint-added]  GET /v1/event-counts/by-name
info	[endpoint-added]  GET /v1/event-counts/by-time
info	[endpoint-added]  GET /v1/events
info	[endpoint-added]  GET /v1/events/{eventId}
```

Against this branch's previous baseline (the contract at commit bfe881b), `oasdiff breaking` reports 4 errors, all `api-security-removed`: `collectionKey` was removed from GET /v1/events, GET /v1/events/{eventId}, GET /v1/event-counts/by-name, and GET /v1/event-counts/by-time. `oasdiff changelog` adds info lines: `queryKey` scheme added, `queryKey` added to the same four operations, and `api-version-not-bumped` (version still 1.0.0). These four endpoints never existed on main, so against main nothing breaks. No SDK or released client used them, so the contract version stays 1 (Q65; before the first SDK release, spec section 9, item 4).

Commands and results (JDK 21.0.12.1+1):
- `(cd server && ./gradlew test verifyJooq --no-daemon)`: BUILD SUCCESSFUL. Tests per module (JUnit XML): `:app` 33, `:ingestion` 5, `:persistence` 12, `:query` 12. 0 failures, 0 errors, 0 skipped. `verifyJooq` passed.
- `scripts/verify-contract.sh`: "ALL CONTRACT CHECKS PASSED".

2026-10-12: Record reconciled. Merged into main in PR #32 on 2026-10-12. Earlier branch and push notes above describe the state before that merge.
