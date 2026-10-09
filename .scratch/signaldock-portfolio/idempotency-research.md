# SignalDock event deduplication: industry research

Research date: 2026-10-08, Asia/Seoul. The tool time was checked as 2026-10-08 00:06 UTC. There is no date difference.

A stable per-event ID supports retry and rebatching. A request `Idempotency-Key` alone cannot replace event-level deduplication. Vendor deduplication results also differ across storage, query, and export stages. SignalDock must state its own contract.

## 1. Research scope and evidence categories

- Accepted scope: Checked against current `AGENTS.md`, `docs/agents/domain.md`, `GLOSSARY.md`, `discovery.md`, ADR-0001, and the user's instructions for this research.
- Source facts: Behavior directly described by the official documents below. External service runtimes were not verified.
- Engineering inference: Design implications derived from source facts. Separate from guarantees stated directly by vendors.
- SignalDock proposals: Minimum design directions not yet adopted. They do not select a DB product or finalize an API schema.
- Unconfirmed: Details that documents omit or contradict. Do not fill gaps with guesses about implementation.

Only official documentation was used as evidence. External code and SDK implementations were not inspected, so external code commit SHAs and code permalinks are not applicable. No private résumé file was opened or uploaded. Only general technical search terms and public documentation URLs were sent to the web. No implementation, tests, or changes to other documents were performed.

Detailed product/DB review belongs to the main agent's `idempotency-design-review.md`. This report covers vendor evidence and brief application proposals.

## 2. Currently accepted SignalDock contract

These are not completed implementation or runtime verification results.

- Return collection success after storage in the Android persistent queue is complete. Retain the same event ID for retries.
- Ktor returns receipt success after DB transaction commit. Retrying an identical stored event succeeds without additional storage or aggregation. The SDK deletes only events with confirmed success from the queue.
- For a parseable batch, store valid events and permanently reject only validation-invalid events individually. Retry transient DB errors and lost ACKs.
- For permanent rejection, retain only limited ID/code/time information on the device. Do not retain raw payloads. Queue removal and diagnostic records must remain consistent.
- Handle contention between the foreground sender and WorkManager. The Dashboard queries the DB. Do not introduce a message broker or separate storage worker.

ADR-0001's UUIDv4 decision concerns the installation ID. Multiple events share that installation ID. Event ID generation format/timing, deduplication namespace/window, DB product, and batch format remain undecided.

## 3. Source facts: identity and window comparison

| Product / unit | Documented deduplication identity | Window and result scope | Evidence |
| --- | --- | --- | --- |
| Segment / event | `messageId`. Does not compare payload content. The guide describes all received events, not source/workspace scope. | Describes 99% deduplication over an approximately 24-hour look-back. Retains at least 24 hours of IDs. Warehouse and Data Lake have separate processing stages. | [S1](https://www.twilio.com/docs/segment/guides/duplicate-data) |
| Amplitude HTTP V2 / event | Same `device_id` + `insert_id` within each app. Says an absent `device_id` is set to a hash of `user_id`. | Ignores subsequent events within the past 7 days. The documented `event_id` is a separate incrementing counter. Do not equate it with SignalDock's event ID based on its name alone. | [A1](https://www.amplitude.com/docs/apis/analytics/http-v2) |
| Mixpanel / query-time event | `(event, distinct_id, time, $insert_id)` within the same project. The project token selects the target project. | Deduplicates immediately at query time. Usually shows the latest ingestion version. This document specifies no fixed retry TTL. | [M1](https://docs.mixpanel.com/reference/event-deduplication), [M2](https://docs.mixpanel.com/reference/import-events) |
| Stripe / HTTP operation | Client-provided request `Idempotency-Key`. Compares against original request parameters. Not event identity. | Saves and reuses the first execution's status/body, including `500`. Keys can be removed after at least 24 hours. Reuse after removal is a new request. | [H1](https://docs.stripe.com/api/idempotent_requests) |

### Segment documentation conflicts and limits

Source facts: Unlike guide [S1], which describes all received events, Partner FAQ [S2] describes a per-source sliding window. The FAQ gives a minimum of 24 hours and an observed value of about 170 days. The FAQ and Help Center [S4] also use stronger deduplication guarantee language than the guide's 99% wording. [S1](https://www.twilio.com/docs/segment/guides/duplicate-data), [S2](https://www.twilio.com/docs/segment/partners/faqs), [S4](https://help.twilio.com/articles/43240224731547)

Unconfirmed: These sources do not establish the actual namespace or handling guarantees for all concurrent requests. Do not use 170 days as a contractual minimum. The guide's 7-day Data Lake period is not the ingestion API window. Do not hide these conflicts by summarizing the contract as complete per-source deduplication.

Source fact: Segment HTTP API can generate IDs at ingestion or accept explicitly supplied IDs. [S1](https://www.twilio.com/docs/segment/guides/duplicate-data)

Engineering inference: If the server generates a new ID on every resend after a missing ACK, it cannot link the original event and its retry. Persist the client-generated ID.

### Amplitude limits

Source facts: HTTP V2 recommends `insert_id`. It explains that retry deduplication is needed even for 500/502/504 responses because events may already have been accepted. For 413, it advises splitting the batch and retrying. [A1](https://www.amplitude.com/docs/apis/analytics/http-v2)

Unconfirmed: The document does not specify whether different payloads with the same identity cause a mismatch error, modify existing content, or how competing requests are handled atomically. It does not establish which internal clock measures the 7 days or whether retries extend the window. Do not extend "ignore subsequent events" into a guarantee of payload comparison or permanent deduplication.

### Mixpanel limits

Source facts: Events remain duplicates even when non-identity properties differ. Query-time deduplication does not apply to raw export. Showing the latest version does not guarantee upsert. Compaction uses `(event, distinct_id, $insert_id)` and the same calendar day. Execution is not guaranteed; references to a few hours later and about 20 days later describe typical processing times. [M1](https://docs.mixpanel.com/reference/event-deduplication)

Unconfirmed: There is no guarantee of a 20-day deduplication window or always storing only one DB row. The page does not establish calendar-day timezone or boundary rules. Keep the description of compaction despite different exact timestamps separate from query-time rules.

## 4. Source facts: partial batches and failure responses

| API | Response meaning confirmed in official documentation | Unconfirmed scope |
| --- | --- | --- |
| Segment HTTP API | Events may not be accepted even when the API normally returns `200`. Recommends checking the Debugger. | HTTP status alone does not prove persistent storage of every event. No SignalDock-style per-event terminal ACK list was confirmed. [S3](https://www.twilio.com/docs/segment/connections/sources/catalog/libraries/server/http-api) |
| Amplitude HTTP V2 | Success gives an `events_ingested` count. `400` provides invalid/missing field indexes and other details. | No explicit contract confirming acceptance of all valid events in a mixed-validation batch was found. Do not assume indexes absent from an error list succeeded. [A1](https://www.amplitude.com/docs/apis/analytics/http-v2) |
| Amplitude Batch Event Upload | Documents success counts and invalid-request `400`. | The inspected document gives neither a complete per-event success/failure result list nor an atomicity guarantee for mixed batches. [A2](https://www.amplitude.com/docs/apis/analytics/batch-event-upload) |
| Mixpanel `/import?strict=1` | Ingests valid events and rejects validation failures. Returns HTTP `400` even for partial failure. Returns index/ID/field/message in `failed_records` and `num_records_imported`. | Does not describe this response as an internal DB transaction commit. Raw export deduplication is separate. [M2](https://docs.mixpanel.com/reference/import-events) |

Engineering inference: One HTTP status is not the same as results for every event in a batch. Mixpanel is a direct example of partial acceptance. However, vendor success responses do not establish SignalDock's post-commit per-event ACK contract.

## 5. Request `Idempotency-Key`: a different contract from analytics

Source facts, Stripe for comparison: Different parameters with the same key cause an error. Validation failures and conflicts with concurrent requests still in progress do not cache a result and can be retried. This page does not explain all HTTP error codes for these cases or the key's full account/endpoint namespace. Stripe is not used as an authoritative analytics source. [H1](https://docs.stripe.com/api/idempotent_requests)

Engineering inference: Sending a header alone does not create atomicity. The server must manage the key, payload, and result consistently. Applying Stripe-style failure-result replay directly to SignalDock's transient DB error retries could keep replaying an error after recovery.

## 6. Engineering inference: retry, rebatching, and ACK behavior

| Situation | Derived implication |
| --- | --- |
| Rebatching as `[E2,E3]` after loss of the ACK for `[E1,E2]` | E2 must retain its event identity even if the batch key changes. A request cache alone cannot find the overlap. Reusing the request key creates a different-payload conflict. |
| Splitting or reordering a batch | Retain event IDs and immutable event content from collection time. Request identity is separate. Products such as Mixpanel that include timestamp/subject in deduplication identity must also retain those values. |
| App calls `track()` twice for the same action | If two new event IDs are created, this is not a transport retry. Merging by payload hash can also discard legitimate repeated actions with identical content. |
| Two senders transmit the same event concurrently | A local lock reduces unnecessary duplicate transmission. The final defense against duplicate storage is a DB unique constraint and atomic transaction. `SELECT` followed by conditional `INSERT` alone does not prevent races. |
| ACK lost after DB commit | The client does not know whether the operation succeeded. Retry the same event and return success again for an identical event already committed. Do not call network delivery exactly-once. |
| Validation failure versus transient DB error | The former is an explicit terminal result. The latter can have an uncertain storage outcome. Keep uncertain events in the queue. |
| Late retry after deletion of the deduplication record | An existing event can be stored as new. Independently chosen queue retention and server deduplication retention can create mismatched boundaries. |

These are inferences about general failure boundaries, not facts about vendor internal DB structures. Vendor concurrency latency, DB durability, payload collision handling, and downstream-wide exactly-once guarantees were not verified.

## 7. SignalDock proposal: minimum path within 20-30 hours

The following are proposals, not adopted decisions. The user's 20-30-hour constraint covers the entire Android portfolio, not only server implementation.

1. Use a stable event ID, DB unique constraint, and atomic transaction. UUIDv4 event IDs are a simple candidate, not an adopted choice. Specify a key such as `(project namespace, event ID)`. The installation ID is a source field. Limit scope to Ktor routes and DB storage/queries; defer a request key cache in the first release. No DB product is selected here.
2. Define canonical payload comparison and collision policy. Compare schema version, installation ID, event name, collection time, and properties; exclude batch/retry/server metadata. Define equivalence for object key order/whitespace, numbers, null/missing values, and array order. Do not rely only on raw strings or one hash. The proposal preserves the existing event and permanently rejects the same key with different content as `EVENT_ID_CONFLICT`. This policy is not yet agreed.
3. Store valid events in one transaction and return per-event ACKs after commit. Reject validation-invalid events individually. Do not return valid-event success on rollback or uncertain commit. Return duplicate-success only after confirming the existing commit and payload. The client deletes only confirmed successes and retries incomplete ACKs. Use a local transaction for permanent-rejection ID/code/time records and queue removal.
4. Share one sender path between foreground and Worker. A local mutex is supplemental and does not replace DB deduplication. The Dashboard queries `COUNT` over stored rows by name/time bucket. Defer separate aggregation tables and per-request counters. Multi-process support, query snapshots, and chart time basis remain undecided.
5. Defer automatic retention deletion in the first demo. Use each event row as its deduplication record. Limit the guarantee to the DB lifetime during which the row is retained. Late retries after reset/deletion can be accepted again. If deletion becomes necessary, define a retry horizon or tombstone. Tombstones retain IDs; deleting original content reduces the evidence available for payload comparison.

During implementation, verify the server DB's durable commit settings and recoverable storage location. Commit confirmation is a condition for successful ACKs, not a lossless guarantee covering DB deletion, recovery losses, device data deletion, or permanent rejection.

## 8. Unconfirmed details and follow-up decisions

| Category | Remaining items |
| --- | --- |
| Vendor evidence limits | Conflicting Segment namespace/guarantee documentation. Amplitude payload mismatch and mixed-batch acceptance. Mixpanel compaction-day timezone/execution timing. Internal transactions and concurrent collision handling at all three analytics vendors. |
| SignalDock decisions | DB product, event ID generation timing/format, namespace, canonical equality, collision policy, maximum batch size/result mapping, malformed envelope handling, repeated duplicate IDs within one batch. |
| Retention decisions | Maximum queue wait/retry duration; deduplication row, original content, and diagnostic retention periods; contract after DB reset/deletion. Do not automatically adopt vendor 24-hour/7-day periods as defaults. |
| Runtime verification | DB durability, Coroutine cancellation, atomic local deletion, foreground/Worker contention, WorkManager interruption/restart, ACK parsing. Not yet executed. |

## 9. Proposed future verification scenarios

Future verification should cover concurrent duplicates, payload conflicts, lost ACKs after commit, rebatching, partial rejection, foreground/Worker contention, and retention boundaries. No tests were created or run in this research.

## 10. Primary sources

All sources were retrieved on 2026-10-08, Asia/Seoul. URLs and sections are retained together. These official documents are dynamic and can change before later review. No code was inspected and no SHAs were invented.

| ID | Official source URL | Reviewed section / evidence | Retrieval date |
| --- | --- | --- | --- |
| S1 | [Twilio Segment: Handling Duplicate Data](https://www.twilio.com/docs/segment/guides/duplicate-data) | 99% deduplication; `messageId`, at least 24 hours, global scope description, no payload comparison, Warehouse/Data Lake distinction | 2026-10-08 KST |
| S2 | [Twilio Segment: Partner FAQs](https://www.twilio.com/docs/segment/partners/faqs) | Does Segment de-dupe messages?; per-source sliding window, at least 24 hours, observed value of about 170 days | 2026-10-08 KST |
| S3 | [Twilio Segment: HTTP API Source](https://www.twilio.com/docs/segment/connections/sources/catalog/libraries/server/http-api) | Errors; difference between `200` and event acceptance | 2026-10-08 KST |
| S4 | [Twilio Help Center: How does Segment deduplicate events?](https://help.twilio.com/articles/43240224731547) | Answer; 24-hour guarantee wording. Checked through search-result text; direct-open body extraction failed. | 2026-10-08 KST |
| A1 | [Amplitude: HTTP V2 API](https://www.amplitude.com/docs/apis/analytics/http-v2) | Event deduplication; Event array keys; Response 200/400/413/500/502/504 | 2026-10-08 KST |
| A2 | [Amplitude: Batch Event Upload API](https://www.amplitude.com/docs/apis/analytics/batch-event-upload) | Responses; SuccessSummary; invalid upload description | 2026-10-08 KST |
| M1 | [Mixpanel: Event Deduplication](https://docs.mixpanel.com/reference/event-deduplication) | How Deduplication Works; Query-Time/Compaction-Time Deduplication; raw export and upsert limits | 2026-10-08 KST |
| M2 | [Mixpanel: Import Events](https://docs.mixpanel.com/reference/import-events) | Validation; properties.$insert_id; Example of a validation error; explicit partial ingestion | 2026-10-08 KST |
| H1 | [Stripe: Idempotent requests](https://docs.stripe.com/api/idempotent_requests) | Status/body replay, parameter mismatch, pruning after 24 hours, validation/concurrent conflict exceptions | 2026-10-08 KST |
