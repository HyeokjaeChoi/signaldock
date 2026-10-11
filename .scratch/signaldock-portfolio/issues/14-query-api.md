# 14. Receipt query API (part of issue 05)

Status: ready-for-agent
Type: task
Blocked by: None
Execution: In review

## Scope and completion criteria

Add the Dashboard query API to `openapi/contract-v1.yaml` and implement it in a new server module `:query` (ADR-0005, section "Server: layered Gradle modules"). Spec section 6 "Dashboard": received-event list and details, counts by type and time, sort, period filter, aggregation by receivedAt, and occurredAt in details. ADR-0002 Q107: the contract version header is required. Follow `.scratch/signaldock-portfolio/dashboard-verification-defaults.md` and the architecture section "DB time budgets and queries" (no aggregate tables or counters).

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
