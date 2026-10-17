# Design review

Status: written 2026-10-15 after the first release (`v0.1.0`), refreshed 2026-10-17 UTC against `main` at SDK `v0.2.0`, after PRs #159 to #161 (opt-out, HTTPS enforcement, client reports: [ADR-0034](../adr/0034-sdk-opt-out.md), [ADR-0035](../adr/0035-sdk-https-enforcement.md), [ADR-0036](../adr/0036-sdk-client-reports.md)). Refresh record: `.scratch/signaldock/issues/63-refresh-stale-docs.md`. Earlier refresh: 2026-10-15 UTC (issue 51). Structure follows the [Google design doc](https://www.industrialempathy.com/posts/design-docs-at-google/) outline.
Every claim cites a file. A claim without a citation is my analysis and is marked "Analysis".

Short paths used below: `SDK tests` = `sdk/sdk-android/src/test/kotlin/local/signaldock/`; `device tests` = `sdk/sdk-android/src/androidTest/kotlin/local/signaldock/device/`.

## Context and scope

Analysis: mobile apps lose network and get killed. An analytics SDK should not lose events then and should not count one event twice. The spec turns this into goals (spec 4, 5, 7).
SignalDock is one monorepo with an Android SDK, an ingest server (Ktor, PostgreSQL), a read-only Dashboard, and an OpenAPI contract between them ([architecture.md](../../architecture.md) "Components and responsibilities").
It is an open-source release from Stackline ([spec](../../.scratch/signaldock/spec.md) section 1).
Scope: one local project, synthetic data, no login (spec 1).

## Goals and non-goals

Goals (spec 1, 3, 4, 5):
- `track()` succeeds when the event is stored on the device. Server receipt is a separate fact.
- Delete a queued event only after a valid ACK that follows the server commit.
- No duplicate server rows when an ACK is lost.
- Ship as an AAR in a Maven ZIP, checked in a separate R8 consumer app ([ADR-0009](../adr/0009-maven-zip-and-consumer-app.md)).
- Contract changes are checked by tools ([ADR-0002](../adr/0002-openapi-json-contract.md)).

Non-goals (spec 1; Q150):
- iOS, Flutter, React Native, Unity, and a WebView JavaScript bridge (Q150), automatic events, `identify`, multiple projects, public internet operation, Maven Central.
- Exactly-once delivery. The design does not claim it ([architecture.md](../../architecture.md) "Lost ACKs and event deduplication").
- Any throughput promise (spec 7). The load numbers are a local record, not an SLA ([perf/results/overload-1/report.md](../../perf/results/overload-1/report.md)).

## Design overview

![Architecture](../architecture/signaldock-architecture.svg)

| Part | Design | Source |
| --- | --- | --- |
| SDK queue and batching | Room table of one transport JSON per event. Limits: 16 KiB per event, 50 events and 256 KiB per batch, 10,000 events or 16 MiB per queue. At the limit, `track()` returns `QueueFull` and keeps old events | spec 4; [ADR-0003](../adr/0003-sdk-storage-decisions.md) item 3; `SDK tests/TrackTest.kt` |
| Delivery, ACK, retry | One `Sender` with one `Mutex` per process. It validates the whole ACK, then applies it in one Room transaction. Backoff is 10 s doubling to 5 min with Full Jitter, stored on disk with the wait that was meant. The sender clamps the stored time to `now + wait`, so a clock moved back does not delay sending. Exception: a retry row migrated from schema 1 has `waitMillis = null` and is not clamped until the next failure stores a wait ([ADR-0025](../adr/0025-sdk-clock-jump.md)). Auth, config, and contract errors hold the queue and recheck every 30 minutes | [ADR-0006](../adr/0006-sdk-delivery-decisions.md) items 5 to 10; `sdk/sdk-android/src/main/kotlin/local/signaldock/internal/delivery/Sender.kt` |
| Scheduling | Foreground trigger at 20 events or 10 s. A unique one-time Worker plus a 15-minute periodic Worker. The Worker stops after 10 batches or 30 s | ADR-0006 item 11; spec 8.A (Q57, Q72) |
| Dedup | `eventId` unique constraint plus content comparison. Same content gives `duplicate`. Different content gives a `conflict` rejection | architecture "Lost ACKs and event deduplication"; spec Q61, Q69 |
| Ingest and storage | Ktor routes. Four slots, four connections, 250 ms admission wait. Timeouts return 503 with `Retry-After`. `/health` is liveness; `/ready` runs one database query and Compose uses it as the server health check. PostgreSQL keeps typed columns and JSONB properties | [ADR-0004](../adr/0004-server-runtime-and-receipt-boundaries.md); [ADR-0026](../adr/0026-server-readiness-and-restart.md); `server/persistence/src/main/kotlin/local/signaldock/persistence/Database.kt` |
| Query and Dashboard | Six read endpoints (event list, event detail, counts by name, by time, by plan status, and client report counts) with a separate query key. `COUNT` and `GROUP BY` at query time. React Dashboard behind an nginx proxy that adds the key | [ADR-0007](../adr/0007-dashboard-query-api.md); [ADR-0008](../adr/0008-dashboard-and-compose-delivery.md); [ADR-0036](../adr/0036-sdk-client-reports.md) item 9; spec Q70, Q128 to Q131 |
| Client reports | The SDK counts the events it drops, by reason, in Room: `queue_full`, `storage_error`, `invalid_event`, `event_too_large`, `server_rejected`. It sends the counts once after a send run that applied a valid ACK, to `POST /v1/client-reports` (collection key, body at most 4 KiB). The server stores one row per entry and sums at query time. A report has no ID and no installation ID, so counts are approximate and a lost response can count twice. The Dashboard shows them in the "Dropped events" card. No retention | [ADR-0036](../adr/0036-sdk-client-reports.md) items 2 to 10; spec Q164; `SDK tests/ClientReportTest.kt` |
| Collection kill switch | Server-only remote control, read from environment variables at start: block by event name or by event `sdkVersion` (per-item rejection `COLLECTION_BLOCKED`, the SDK deletes the event), and a shed switch that answers 503 with `Retry-After`. The server cannot stop collection on the device: the SDK still queues until `QueueFull`. Later path: a response header that the SDK reads (option B) | [ADR-0037](../adr/0037-server-collection-kill-switch.md); spec Q165; `SRV:CollectionControlTest` |
| Contract | OpenAPI is the source. Generated Kotlin and TypeScript are tracked in Git and checked for drift. Header `SignalDock-Contract-Version: 1`. Additive changes keep version 1. A request-level 400 means a contract bug only; content problems of one event are per-item rejections ([ADR-0027](../adr/0027-request-400-contract-only.md)). Support policy: an old version stays at least 12 months and until its traffic is low; the SDK reads `Deprecation` and `Sunset` headers, the server does not send them yet ([ADR-0030](../adr/0030-sdk-contract-deprecation-warning.md)) | ADR-0002; [ADR-0022](../adr/0022-server-tracking-plan-status.md) item 7 |
| Optional tracking plan | The server marks events `planned`, `unplanned_event`, or `invalid_properties`. It never rejects for plan reasons | ADR-0022; spec Q155 |

## Alternatives considered

Only alternatives that the repo records are listed.

| Alternative | Why it was rejected | Source |
| --- | --- | --- |
| Protobuf for ingest, JSON for queries | Two codecs and two generation paths. No binary-transport need | ADR-0002 |
| Shared Kotlin DTO artifact | Public API types, Room entities, jOOQ records, and DTOs must stay apart by role | ADR-0002; architecture "Contracts, generated sources, and JSON" |
| Several SDK modules or a fused AAR | Fused library plugin is Preview in AGP 9.x. Kotlin `internal` is per module, so DTOs would become public | [ADR-0005](../adr/0005-module-structure.md) |
| Exactly-once delivery, a batch `Idempotency-Key`, a response cache, Redis | Not needed. `eventId` uniqueness and content comparison give safe retries. Cost: dedup lasts only while the row exists | spec Q69; architecture "Lost ACKs and event deduplication" |
| Aggregate tables, counters, aggregation Worker | Query with `COUNT` and `GROUP BY` first, measure later | spec Q70; architecture "DB time budgets and queries" |
| DI framework in the SDK | Manual DI at one composition point | spec Q87 |
| WorkManager's own backoff | The SDK keeps one persisted retry time shared by foreground and Worker. The Worker never sleeps | ADR-0006 item 11 |
| `lifecycle-process` dependency | `ActivityLifecycleCallbacks` is enough | ADR-0006 item 11 |
| Collection key for query endpoints | An app can expose the key. Queries now need a separate query key | ADR-0007 "History"; spec Q128 |
| ID kept in Auto Backup storage | The SDK prefers separate installation IDs over continuity. Cost: unsent events can be lost after restore | [ADR-0001](../adr/0001-installation-scoped-identity.md) |
| A 216-run pool-size benchmark | Large headroom in two pilots. A pool comparison would likely show no difference | spec Q133; [ADR-0013](../adr/0013-server-load-measurement-harness.md) |

Gap: the repo records the choice of Room (spec 2) and of "one-time Worker plus 15-minute periodic" (spec Q57), but not the options that lost. I do not list invented ones.

## Cross-cutting concerns

Security (details in [threat-model.md](threat-model.md)):
- The collection key is public by design and write-only (spec Q128). A separate query key guards reads. The Dashboard bundle never holds it (ADR-0008 "Key check").
- Keys are compared in constant time (`server/app/src/main/kotlin/local/signaldock/server/RequestGuards.kt`).
- TLS: the SDK requires an `https` endpoint by default. An `http` endpoint needs `allowInsecureEndpoint = true`, and the SDK never upgrades `http` to `https` ([ADR-0035](../adr/0035-sdk-https-enforcement.md); `sdk/sdk-android/src/main/kotlin/local/signaldock/Results.kt`, `internal/validation/Validation.kt`). The demo app and the consumer app set the flag when their endpoint starts with `http://` (`DemoApplication.kt`, `ConsumerApplication.kt`). Android also blocks cleartext unless the app's network security config allows it (ADR-0009 item 5; spec Q134).

Privacy:
- Installation-scoped UUID, no user identity, no `identify` (ADR-0001; [integration guide](../integration-guide.md) section 8).
- Logs never hold event content, names, IDs, credentials, or bodies (spec Q115; `SDK tests/InitContractTest.kt`).
- Queue and ID are excluded from backup (`SDK tests/StorageTest.kt`, device `install_check` in `device tests/DurabilityPhases.kt`).
- Opt-out: `setOptOut` and `clearQueue` ([ADR-0034](../adr/0034-sdk-opt-out.md), spec Q156). Gap: no consent text. No server retention or delete function: excluded on purpose for local-only operation ([ADR-0028](../adr/0028-server-production-features-excluded.md), spec Q157).
- XSS and SQL injection have tests ([threat-model.md](threat-model.md), PR #111).
- Excluded on purpose (ADR-0028): rate limit and `429`, access log, key policy, retention, and Dashboard login. They are required before any non-local use.

Observability:
- Device: `diagnostics()` shows queue size, last ACK, next retry, last error, and 100 rejections (spec 6; ADR-0003 item 7). Dropped-event counts are not in `diagnostics()`; they go to the server and show in the Dashboard "Dropped events" card (ADR-0036 items 10 and 11). Gap: an SDK older than 0.2.0 sends no report, so an empty card does not prove that no event was dropped.
- Server: stage timings (admission, connection, commit, lock and statement timeouts) go to logs (`Database.kt`, `Application.kt`). Optional pool stats (`SIGNALDOCK_POOL_STATS_MS`).
- `/health` is liveness. `GET /ready` checks the database with `SELECT 1` and returns 503 when it fails (`Application.kt`, ADR-0026). Compose shows the server as `unhealthy` (`docker compose ps` only). `DatabaseOutageTest` covers a real PostgreSQL stop and start. Gap: the live Compose check is recorded in a PR #110 comment only; no committed result file.
- Gap: no metrics endpoint and no alerting (ADR-0026 item 4). The Compose restart policy was not shown to trigger.

## Scale: what breaks first at 10x and 100x

This section is analysis. The repo measured only the numbers in the next table. Everything after it is reasoning from those numbers, with assumptions stated. Nothing here was run at 10x or 100x.

Measured baseline (local Docker, DB 2 CPUs, server 2 CPUs, pool 4, 100,000 seed rows; the second pilot ran on a dirty tree):

| Fact | Value | Source |
| --- | --- | --- |
| Sustained load | 5 requests/s of 20 events (100 events/s) plus 5 Dashboard clients | [pilot-2 report](../../perf/results/pilot-2/report.md) |
| Collect latency p50 / p95 | 19.1 / 30.2 ms | pilot-2 |
| By-name, by-time, list p95 | 170 / 169 / 145 ms (p50 up to 131 ms) | pilot-2 |
| CPU (average / max) | server 6.9 / 17.5 %, DB 11.9 / 44.6 % | pilot-2 |
| Pool | max active 4 and max pending 4 of pool 4; connection wait p95 75 ms | pilot-2 |
| Row size | about 1 KB per event row (139 MB table for about 136,000 rows) | pilot-2 |
| Overload run (concurrency 2, admission 100 ms) | 3,703 of 5,805 collect requests got 503 with `Retry-After: 1`; no other status; no row for a 503 request; back to all-200 within 5 s; collect p95 22.9 ms in recovery | [overload-1 report](../../perf/results/overload-1/report.md) |
| Stored during that overload phase | 2,102 accepted requests of 20 events in 30 s, about 1,400 events/s, with 150 by-name requests/s competing | overload-1 |
| SDK `track()` p95 | 0.95 ms (release), 2.3 ms (debug), at 100 events/s, one emulator | [perf/sdk-results/summary.md](../../perf/sdk-results/summary.md) |
| SDK drain of 1,000 offline events | 20 batches; 1.7 s after the stored retry wait (release) | sdk-results summary |

Server, first bottleneck: the shared slots and pool, then query cost.
- Ingest alone is not first. The overload run stored about 1,400 events/s on 2 slots (see table), so 10x (1,000 events/s) is inside what one run showed. This holds only for that profile.
- The first limit is the four slots and four connections that ingest and Dashboard queries share. Pilot-2 already shows a full pool at 100 events/s. Analysis: each Dashboard client polls 5 queries every 5 s: list, by name, by time, by plan status, and dropped events (`dashboard/src/App.tsx`, `POLL_MS` in `dashboard/src/polling.ts`). That is 1 query/s per client. No run measured 5 queries per client. Fifty clients (10x) would send about 50 queries/s. At about 100 ms each, that needs about 5 slots, and there are 4. Queries alone would take all slots. Ingest waits, admission waits pass 250 ms, and the server answers 503 (the behavior seen in overload-1).
- Query cost is second. By-name and by-time count all rows of the period and the repo has no aggregate table (spec Q70) and no index on name (ADR-0007 "No index"). Measured: p50 up to 131 ms at about 136,000 rows. At 100 events/s the table gains 8.64 million rows per day, so a 30-day window holds hundreds of millions. If cost grows linearly with rows (an assumption, no run tests it), the 1,000 ms by-name target (pilot-2 table) is reached near 1 million rows, about 3 hours of ingest at 100 events/s.
- Storage is third. About 1 KB per row gives about 9 GB per day at 100 events/s, about 90 GB at 10x, about 900 GB at 100x. There is no TTL (spec 5) and no partitioning in the repo.
- At 100x (10,000 events/s, 500 requests/s of 20 events) one Ktor instance, one PostgreSQL, and one WAL fsync per batch are all single points. I have no number that says which fails first. The commit stage already shows p95 146 ms at 5 batches/s (pilot-2), so I would test the commit path first.
- Retry herd: the server always sends `Retry-After: 1` (`Application.kt`). SDK waits are `max(Full Jitter, Retry-After)` (`Sender.kt`), so after an outage the spread comes only from the SDK jitter, not from the server.

What I would change, in this order (none of it is built):
1. Separate slot and pool budgets for ingest and query, so Dashboard polling cannot starve ingest.
2. Move aggregates off the hot table: a time-bucket rollup or a read replica. This reverses spec Q70, so it needs a new ADR and a measurement first.
3. Partition `events` by `received_at` and add a retention job.
4. Make `Retry-After` grow with the shed rate.
5. Metrics for the stage timings that exist as logs. (The DB check exists now: `/ready`, ADR-0026.)

SDK, first bottleneck: serial sending, then the queue cap.
- Batches go one at a time under one `Mutex` (ADR-0006 item 10), 50 events at most. Analysis: on a 100 ms round trip that is at most about 500 events/s per device. One device does not make 1,000 events/s, so this does not matter at 10x or 100x of the fleet. It matters if one host app emits bursts.
- A Worker run sends at most 10 batches, 500 events (spec Q72). A full 10,000-event queue needs 20 runs. Analysis: this is slow only if the foreground never runs.
- The queue cap is 10,000 events (spec 4). At 100 events/s from one app it fills in at most 100 s offline. The 16 MiB byte limit (spec 4) can bind first: events larger than about 1.6 KiB on average reach `QueueFull` sooner. Then `track()` returns `QueueFull`. Cost: the newest events are dropped, not the oldest.
- `track()` cost did not rise with load in the three scenarios (sdk-results summary), but each ran 1,000 events or fewer.

## What I would do differently

- Write the rejected options into the ADRs when the choice is made. The repo cannot show why Room and the Worker path won (gap above).
- Start with a server health and metrics path before load tests. The pilots needed log parsing to see the pool.
- Keep the SDK retry clock monotonic from the start. The sanity bound now exists (ADR-0025), but the clock is still wall-clock time and a forward jump sends one batch early (see [failure-modes.md](failure-modes.md), clock skew).
- Make the poison-event rule explicit at design time. It is now fixed: a request-level 400 means a contract bug, and content problems are per-item (ADR-0027). A third-party client can still hold a queue (failure-modes, poison event).
- Make the Dashboard and ingest separate pools from day one.
- Run the pool comparison only if a real question needs it. Q133 was a good cut.
- Review gates should print all findings. See the [postmortems](postmortems/2026-10-14-review-findings-missed.md).
