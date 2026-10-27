---
status: accepted
---

# SDK client reports: dropped event counts (issue 61, spec Q164)

The user chose (Q164, option a) an SDK health report like Sentry client reports. The SDK counts the events it dropped, by reason, and sends the counts to a new endpoint. The batch contract is unchanged. This ADR records the implementer decisions. The user can change them.

## Decisions

1. **A new endpoint, not a new field.** `POST /v1/client-reports` is new. `POST /v1/events:batch` keeps `additionalProperties: false` (ADR-0002 amendment, Q67), so an old server would reject a batch that has a new field. An old server answers 404 to the new route, and the SDK discards the report (item 6). The contract change is additive; the contract version stays 1 (Q65). The baseline `fixtures/contract/contract-v1.baseline.yaml` is updated on purpose. `oasdiff breaking` reports nothing; `oasdiff changelog` lists two added endpoints (the commands and outputs are in the issue 61 file). The new query route is `GET /v1/client-report-counts`.
2. **Reasons: only what the SDK detects.** `queue_full` (`track()` returned `QueueFull`), `storage_error` (`StorageError`), `invalid_event` (`Invalid`, any reason except size), `event_too_large` (`Invalid` with `EVENT_TOO_LARGE`), `server_rejected` (a permanent rejection in a valid ACK, counted in the same Room transaction that removes the event: Q158 per-item rejection, also `EVENT_ID_CONFLICT`). Not counted: opt-out and queue clearing by the host (Q156), because the host chose them; network and server failures, because the SDK keeps those events; expiry, because the queue has no TTL (spec section 4). A new reason is a contract change: the generated models reject an unknown enum value (as for `PlanStatus`, ADR-0022).
3. **Wire format.** `{"sdkVersion":"0.1.0","discardedEvents":[{"reason":"queue_full","count":3}]}`. Request schemas are strict. One entry per reason, 1 to 5 entries, a count from 1 to 1,000,000, `sdkVersion` 1 to 64 characters, body at most 4 KiB. The response is 204 with no body. Same auth and headers as ingest: `Authorization: Bearer <collection key>` and `SignalDock-Contract-Version`.
4. **Privacy (ADR-0001).** A report has no installation ID, event name, event ID, or property. The server cannot link a report to an installation or an event. This is the minimum that answers "which SDK version drops how many events and why".
5. **The SDK keeps the counters in Room.** Table `dropped_counts` (reason, count) in database schema 3 with `MIGRATION_2_3` (`schemas/.../3.json`; ADR-0025 rule: a new version, a migration, a new schema file). Why Room and not memory: it is the existing storage pattern, a crash or restart does not lose the counts, and the `server_rejected` count commits together with the queue change. A counter stops at 1,000,000 (saturating add, no overflow). Counting is best effort: a failure to write a counter is logged as a WARN with the exception class and never changes the `TrackResult` or reaches the host. Cost: a `track()` that drops an event does one extra small write, so a host that calls `track()` in a loop while the queue is full does one write for each call. A drop that cannot be written (for example a full disk) is not counted; this is most likely for `storage_error`.
6. **Sending.** `Sender.run` sends the report once, after the event batches of that run, only if the run applied at least one valid ACK and a counter is not zero. Why: the server is then known to be reachable and the key valid, no extra Worker run is needed, and a send cycle that has no event sends no HTTP (spec 8.A). It holds the sender mutex, and it never reads or writes the retry state, so a failed report cannot delay events. The result of the report:

   | Answer | Counters |
   |---|---|
   | 2xx | Subtract the sent counts |
   | 404 (old server), 400, 401, 403, 413, 415, other 4xx, 3xx, 501, 505 | Subtract the sent counts: the report is discarded, no retry |
   | Network error, timeout, 408, 429, other 5xx | Keep for the next cycle |

   "Subtract" removes only the sent amount, so a drop counted during the HTTP call stays. A counter of an unknown reason (for example after a downgrade) is not sent and stays. After a 404 the SDK does not remember it: the next cycle with a non-zero counter sends one more report and gets one more 404. This costs one small request for each such cycle. Retry-After is not used: the next cycle is the retry.
7. **Counts are approximate.** A report has no ID and the server does not deduplicate. If the server stores a report and the response is lost, the SDK sends the same counts again, so they count twice. A device that is uninstalled or cleared loses its counters. The query response and the Dashboard say that the counts are approximate.
8. **Server.** The route is in `ingestionModule`. Check order: contract version (400), collection key (401), content type and encoding (415), size (413), syntax and unknown or repeated fields (400), rules (400 `INVALID_REPORT`), storage, 204. A count that is not a number is `MALFORMED_REQUEST`; a number that is not a plain integer from 1 to 1,000,000 (`3.5`, `1e3`, `3.0`, `0`) is `INVALID_REPORT`. The migration also adds the table to the jOOQ codegen include list. A request-level 400 is acceptable here, because a report has no queue to hold (Q158 is about event batches). The table `client_reports` (migration `V3__create_client_reports.sql`) has one row per entry: `received_at`, `sdk_version`, `reason`, `count`, with CHECK constraints and an index on `received_at`. The counts are summed at query time with `SUM ... GROUP BY` (Q70: no aggregate table, no counter). The row is never joined to an event. There is no retention job (ADR-0028).
9. **Query API.** `GET /v1/client-report-counts?from=&to=` takes the query key, the contract header, and the same period rules as the other counts (at most 30 calendar dates, `PERIOD_TOO_LONG`). It returns at most 100 rows of SDK version, reason, and count (largest first) and `truncated`. It takes no event filters: a report is not an event.
10. **Dashboard.** A fifth polled query with the same polling, backoff, and contract-error rules (Q108, Q109). The card "Dropped events" lists SDK version, reason (a translated label, with the wire name), and count, with the total of the shown rows, an empty state, and a truncation note, in English and Korean (ADR-0015). It follows the period, not the event filters. An empty card does not prove that no SDK dropped events: an older SDK sends no report.
11. **No public API change.** The API dump (`sdk-android.api`) and the dependency baseline do not change. `diagnostics()` does not show the counters.

## Test mechanism

- `ClientReportTest` (`sdk-android`, Robolectric, real Room): each result maps to a reason; counting through `track()`; saturation; restart; the `server_rejected` count in the ACK commit; one report per send cycle after the batches; no report without a valid ACK or without a counter; each answer class (2xx, permanent, transient) and the retry state staying untouched; a drop during the HTTP call; a failing report not hiding the batch result; the body equals `fixtures/roundtrip/client-report-request.json`.
- `TransportTest`: the report request uses the report path, the Bearer key, the contract header, the JSON type, and the exact body.
- `MigrationTest`: a schema 1 database reaches schema 3 and the table works.
- Server: `ClientReportTest` in `:ingestion` (rules), `:persistence` (store, migration, `EXPLAIN` of the period query) and `:app` (real PostgreSQL: validation, auth, limits, storage, query, and a POST of `fixtures/roundtrip/client-report-request.json` as the drift check with the SDK test). Dashboard: `dropped.test.ts`, `api.test.ts`, and `App.test.tsx` in both languages.
- `contract-check` and `npm run contract-check`: round-trip fixtures; an unknown reason fails to decode.

## Not done

- No idempotency key for reports, no per-installation counts, no time bucket sent by the SDK (the server uses the receipt time).
- No in-memory buffer for counters that cannot be written.
- No report when no event is sent (no HTTP for an empty queue, spec 8.A). Counters wait for the next send cycle.
- No `diagnostics()` field for the counters; it would change the public API.
- No retention or delete of report rows (ADR-0028).
- Gap: not run on a device or emulator. JVM (Robolectric) tests and a real server through the server tests only; no end-to-end run of SDK against server. (Closed for `server_rejected` by the 2026-10-17 Amendment.)
- Gap: with opt-out (ADR-0034, in review as PR 159), a new `TrackResult` case is not a drop unless it is added to `dropReason()` on purpose.

## Amendment 2026-10-17: end-to-end run (issue 65)

Case 5 of `scripts/e2e-delivery.sh` closes the "no end-to-end run" Gap for one reason. The real SDK (consumer app, R8 release) on an emulator gets a per-item rejection from the server. The fault proxy `mutate` mode causes it, as in case 3. The SDK counts one `server_rejected`, sends `POST /v1/client-reports` after the batch, and the server answers 204. The case checks that `GET /v1/client-report-counts` (the Dashboard path, query key added by nginx) goes up by exactly 1 and that `client_reports` has one new row. No product code changed.

- Run: emulator-5556 (faults37, API 37), `E2E_SKIP_BUILD=1 scripts/e2e-delivery.sh 3 5 1`: PASS for cases 1, 3, and 5 (details in the issue 65 file).
- Gap: the other four reasons are not caused on a device. The Dashboard card is not rendered in a browser; the case reads the same endpoint.
- Not run in CI (needs an emulator and Docker, as ADR-0010 item 3).

## Amendment (2026-10-27)

[ADR-0046](0046-sdk-platform-field.md) adds `sdkPlatform` to the client report body (optional; a missing value is stored as `android`), to the count items of `GET /v1/client-report-counts` (required), and an optional `sdkPlatform` filter on that endpoint. Counts then group by platform, SDK version, and reason, and the 100-row limit of decision 9 orders by count, then platform, SDK version, and reason. The text of decision 9 above is the state before that change.
