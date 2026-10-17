---
status: accepted
---

# Server-side collection kill switch (issue 67, spec Q165)

The user chose (Q165, option A, 2026-10-17 UTC) a remote control that lives only on the server. The SDK does not change. The wire format and the contract do not change. This ADR records the implementer decisions. The user can change them.

## Context

Before this change, the operator had no way to stop a bad event, or a bad SDK release, from reaching the server. The threat model and the failure modes list this as a gap. Other products use these patterns:

| Product | Pattern |
|---|---|
| Sentry | The server answers with a rate-limit header (`X-Sentry-Rate-Limits`). The SDK reads it and drops events for a time. |
| Amplitude, Mixpanel | The server drops events by rule after they arrive (block or drop lists). The SDK does not know. |
| Segment, Firebase | The SDK pulls a remote config (tracking plan, enabled flags) and decides on the device. |

The first and third patterns need an SDK change. The second needs none. Option A is the second pattern.

## Decisions

1. **Block by event name and by SDK version.** `SIGNALDOCK_BLOCKED_EVENTS` and `SIGNALDOCK_BLOCKED_SDK_VERSIONS` hold comma-separated lists. Each item is trimmed, and blank items are ignored. Matching is exact and case-sensitive. The event name is the event `name`. The SDK version is the `sdkVersion` field of each event, which is the only SDK version that the server already receives for ingest (there is no version header). A blocked event gets the existing per-item `rejected` ACK (ADR-0027, Q158). The SDK deletes it, stores a diagnostic, and counts `server_rejected` (ADR-0036). Other events of the batch are stored as usual. The rest of the ingest rules do not change: an event that is already invalid keeps its own code.
2. **Rejection code `COLLECTION_BLOCKED`.** The contract has no list of rejection codes: `ItemAck.code` is a free string, and the SDK keeps any non-blank value (`UNSPECIFIED` if blank). No existing code fits: `INVALID_EVENT`, `INVALID_TEXT`, and the other codes say the event is malformed, and `CONFLICT` and `EVENT_ID_CONFLICT` say the event ID clashes. A new value of this free string is not a schema change, so `oasdiff` reports nothing and the contract version stays 1. Gap: no document lists the codes, so this ADR is the list entry.
3. **Shed switch.** `SIGNALDOCK_INGEST_SHED=true` makes `POST /v1/events:batch` and `POST /v1/client-reports` answer `503` with `Retry-After` and the error code `TEMPORARILY_UNAVAILABLE` (the same body shape as ADR-0026 and ADR-0004). The SDK keeps the queue and waits (ADR-0006 item 5 and 9). The check runs after the contract version check and the collection key check, so a caller without the key learns nothing about the state. The route does not read the body and does not touch the database. `/health`, `/ready`, and the query routes stay up, so the Dashboard works. `SIGNALDOCK_INGEST_SHED_RETRY_AFTER_SECONDS` sets the `Retry-After` value in seconds. The default is 1, the value that the existing 503 answers use. Allowed values are 1 to 86400. Why a limit: the SDK never caps `Retry-After` (ADR-0006), so a very large value would silence every device for a long time.
4. **Config source: environment variables, read at start.** This is the smallest source and it fits the local-only operation of ADR-0028. A change needs a restart of the `server` container (`docker compose up -d server`). A malformed value stops the start with an error that names the variable (`SIGNALDOCK_INGEST_SHED` must be `true` or `false`; blank means off). The start logs `collection control: shed=<bool>`. It does not log the lists. Compose passes the four variables with empty defaults, and `.env.example` documents them.
5. **No DB table, no editor.** A table or a Dashboard editor would add a write path, auth for it, and hot reload. The design sources give no reason for them: the Dashboard has no login (ADR-0028), and one operator restarts one local server. No design source needed a different choice.
6. **Default.** With nothing set, no event is blocked and no request is shed. A test checks this.

## Limits

- The server cannot stop collection on the device. The SDK still collects and queues events. A blocked event is sent once, rejected, and deleted, so it costs one request for each batch that holds it, and it does not need to fill the queue. While the shed switch is on, or when collection is faster than delivery, the device keeps its events until the queue is full (`QueueFull`, ADR-0036 counts it as `queue_full`). This is why option A is a mitigation and not a real stop.
- A same-ID group in one batch is atomic (ADR-0004): when one member is blocked, the other members get `EVENT_ID_CONFLICT`, so none is stored.
- Event names that contain a comma cannot be blocked, because the list uses commas.
- A change needs a restart. There is no hot reload and no audit trail (ADR-0028).
- The block does not use the installation ID or any property. It does not block by event ID.
- The shed switch has no per-key or per-IP rule. It affects every caller of the collection key (ADR-0028: the 503 shedding stays global).
- Option B is the later path: a response header in the style of ADR-0030 (`Deprecation` and `Sunset`) or the Sentry rate-limit header, which the SDK reads and uses to stop collecting on the device. It needs an SDK change, a public API decision, and a contract change.

## Test mechanism

`CollectionControlTest` (`server/app`, real PostgreSQL through Testcontainers): nothing is blocked by default; a blocked name is `rejected` with `COLLECTION_BLOCKED` and the other events are stored; a blocked SDK version is `rejected`; shed gives 503 with the configured `Retry-After` (and 1 by default), stores nothing, and a request with no key still gets 401; environment parsing defaults and malformed values.

Compose smoke test (2026-10-17 UTC, separate project `-p sdkill` on ports 18080, 15173, and 15432; the record is in the issue 67 file): with `SIGNALDOCK_BLOCKED_EVENTS=noisy_event`, a batch of one blocked and one allowed event got `rejected` with `COLLECTION_BLOCKED` and `accepted`, and the database held only the allowed row. With `SIGNALDOCK_INGEST_SHED=true` and `SIGNALDOCK_INGEST_SHED_RETRY_AFTER_SECONDS=120`, both ingest routes answered 503 with `Retry-After: 120`, and the start log had `collection control: shed=true`. Gap: the `SIGNALDOCK_BLOCKED_SDK_VERSIONS` variable was not run through Compose (the server test covers it).
Gap: no SDK test against a server with a block. The SDK side of a per-item rejection and of a 503 is already tested (ADR-0036, ADR-0006).
