---
status: accepted
---

# Dashboard query API decisions

Issue 14 adds the Dashboard query API to contract 1. Spec section 6 "Dashboard", section 12, and `.scratch/signaldock-portfolio/dashboard-verification-defaults.md` fix what the Dashboard shows. ADR-0002 (Q107) requires the contract version header. These decisions fill the gaps those sources leave. They are implementer decisions that the user can change.

**Authentication.** Spec Q128 to Q131 (2026-10-12) separate the keys. The four query endpoints accept only a query key (`Authorization: Bearer`, security scheme `queryKey`). Ingestion accepts only the collection key. Apps can expose the collection key, so it is write-only and cannot read stored events. A missing, wrong, or other-role key on a query endpoint returns 401 `QUERY_KEY_INVALID`, never 403, so the server does not reveal that a value is a valid collection key. Ingestion keeps 401 `COLLECTION_KEY_INVALID`, and a query key there gets that 401. The check order is contract version (400), key (401), then parameters (400). The server reads the query key from `SIGNALDOCK_QUERY_KEY` and does not start if it is missing, blank, or equal to `SIGNALDOCK_COLLECTION_KEY`. It has no other length or format rule, and it never logs key values. There is no key issuance, rotation, or key table. The Dashboard bundle never holds the query key: a same-origin proxy adds the header (see "Left open"). The query key is not user authentication and does not protect a public service.

**Endpoints.**

| Endpoint | Purpose |
|---|---|
| `GET /v1/events` | List, `sort` `receivedAt_desc` (default) or `receivedAt_asc`, `limit` 1 to 100 (default 50), opaque `cursor` |
| `GET /v1/events/{eventId}` | Details with `occurredAt`, `receivedAt`, `sdkVersion`, and `properties`; 404 `EVENT_NOT_FOUND` |
| `GET /v1/event-counts/by-name` | Counts by event name, at most 100 names, `truncated` when more exist |
| `GET /v1/event-counts/by-time` | Counts by `receivedAt` bucket, `bucket` `hour` or `day`, empty buckets filled with 0 |

List and counts are separate endpoints because Q109 keeps list and aggregate polling state separate. They take the same `from`, `to`, `name`, `installationId`, and `eventId` parameters, so one set of filters applies to both (defaults, item 7).

**Period.** `from` (inclusive) and `to` (exclusive) are RFC 3339 instants with an offset and exact milliseconds (Q80, Q82, Q83). The Dashboard converts project-timezone calendar boundaries to instants. The server rejects a period that touches more than 30 calendar dates in the project timezone with `PERIOD_TOO_LONG`; it never truncates (defaults, item 2). The default bucket is `hour` when the period touches one date and `day` otherwise; an explicit bucket is accepted up to 720 buckets (defaults, item 6).

**Project timezone.** The server reads `SIGNALDOCK_TIME_ZONE`, default `Asia/Seoul` (Q78, Q79), for calendar-date counting and bucket boundaries. Only region IDs are accepted, because PostgreSQL reads offset IDs with the opposite sign. `by-time` responses carry `timeZone` so the Dashboard can show it.

**Pagination.** The cursor is an opaque string that holds the sort direction, `receivedAt`, and `eventId`. A cursor is valid only with the `sort` that made it; otherwise `INVALID_CURSOR`. The query uses a row comparison on `(received_at, event_id)` and the existing index `events_received_cursor_idx`, fetches `limit + 1` rows, and returns `nextCursor` only when another page may exist. The cursor is not a snapshot (architecture.md "DB time budgets and queries").

**No new index.** `QueryStoreTest` runs `EXPLAIN` on 20,000 rows after `ANALYZE`. The first page, a keyset page, and the count by name over a one-day period all use `events_received_cursor_idx` with no sequential scan. Filters on `name` and `installationId` have no index and are applied to the rows of the period, which is at most 30 calendar dates. Add an index only after measurement shows a need (performance plan). No migration was added.

**Strict requests, tolerant responses.** Unknown, repeated, or invalid request parameters are rejected with 400 `INVALID_PARAMETER`. Response schemas do not set `additionalProperties: false`, so a client ignores added fields (ADR-0002 amendment, Q68). Errors use `ErrorResponse`. A temporary database failure returns 503 with `Retry-After`, as ingestion does.

**Modules.** `:query` holds parameter parsing, limits, cursor, and bucket rules. It has no Ktor, jOOQ, Flyway, JDBC, or serialization dependency. It depends on `:ingestion` only for `PropertyValue`, so stored numbers keep their literal. `:persistence` holds the read-only SQL (`SET TRANSACTION READ ONLY`, the same statement and lock limits as ingestion). `:app` holds routes and generated DTOs.

**Left open.** Browser access is resolved by Q129: nginx (Docker Compose) or the Vite dev proxy serves the Dashboard and proxies `/v1/*` to the server on the same origin, adding the query key. The server has no CORS, and the Dashboard port binds to `127.0.0.1` only. Issue 15 implements this. Production values for query time budgets stay undecided, as for ingestion.

History: until 2026-10-12 the query endpoints used the collection key with 401 `COLLECTION_KEY_INVALID`, and CORS was left to issue 15. Q128 to Q131 replaced that with a separate query key and a same-origin proxy.
