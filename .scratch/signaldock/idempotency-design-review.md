# Idempotency design review

Status: Design review record. 2026-10-08. Q55 adopted the policy that the same eventId with the same content succeeds as a duplicate, while different content is rejected as a conflict and the existing row is preserved. This does not mean all items are agreed. See spec.md for the current decision status. Implementation and runtime verification are pending.

Q69 adopted eventId-based idempotency without a separate batch Idempotency-Key or response cache. A retry response can change from accepted to duplicate. Other proposals below, including the aggregation implementation, were not automatically adopted.

## Review perspective

The Staff Product Engineer review considers data accuracy, integration API usability, failure recovery, guarantees that can be explained, and the 20~30-hour implementation scope. This role is not a separate skill name. At the user's request on 2026-10-08, `skills/ponytail` from `DietrichGebert/ponytail` was installed in the Codex user-wide directory `~/.codex/skills/ponytail`. SKILL.md was read directly and applied in full mode. The installed commit is `552acd5efd0aeae2583a12efe39373d2f076f25e`. An independent subagent was also asked to read the same skill and design documents and review them.

## Guarantees required by existing agreements

When the SDK sends the same event again, stored and aggregated counts must not increase. Requests can repeat after a lost response, process restart, or contention between foreground and Worker senders. A batch can change during retries, so request-level equality alone cannot identify duplicate events.

For example, after sending `[A, B, C]` without receiving a response, a later request containing `[B, C, D]` must still leave only one B and one C. Deduplication must cover events shared by different requests.

## Minimum design proposal

- The SDK creates `eventId` once when it creates an event and persists it with the event body. It does not regenerate the ID during transmission. The exact UUID version is a separate decision.
- The server prevents duplicate inserts with a DB unique constraint and transaction. Application code that reads before inserting is not sufficient to guarantee this.
- The same ID and event content succeed as an already received event. The same ID with different content is rejected as a conflict without overwriting existing data. Content comparison uses fixed fields from collection time and excludes request time, batch composition, and server receipt time. Define the comparison rules with the schema.
- Responses for the SDK distinguish results by event ID, such as `accepted`, `duplicate`, and `rejected`. These names are examples. Confirm accepted/duplicate only after DB commit. Do not delete events with missing or invalid responses.
- Check repeated IDs within the same batch first. The proposal treats identical content as one event and rejects inputs for that ID as a conflict when their content differs. Do not return both success and rejection for one ID. Preserve existing DB data.
- Separate request tracing IDs from idempotency identity. Following the research, the recommendation for the first release is to use event IDs as idempotency keys without a per-batch `Idempotency-Key` or response cache. Request tracing IDs support diagnostics and do not guarantee deduplication.
- The Dashboard aggregates deduplicated event rows. A separate incrementing counter would need the same transaction so that a failed duplicate insert does not increase the counter. Prefer queries without a separate counter for the first version.

### Proposed server processing order

1. Validate the request structure and each event. If the request itself cannot be parsed, do not create per-event success results.
2. Insert valid events in a transaction and compare ID conflicts with existing content. Distinguish a new insert, an identical event, and a conflict with different content.
3. Commit the transaction for valid events. Do not return storage success if a DB failure causes rollback.
4. Return per-event results after commit. The SDK validates the response before updating queue state for those events.

Partial batch acceptance saves valid events while excluding invalid ones. It does not require a separate transaction for each valid event or classification of DB failures as permanent event errors.

## Scope and limits

- If a customer app calls `track()` twice for the same action, the default result is two different events. SDK retry deduplication and prevention of duplicate app calls are separate problems. Exposing a customer-provided idempotency key in the first version is a separate decision; do not add it automatically.
- If cancellation of `suspend track()` overlaps with completed local storage, the event may be stored even though the caller receives no success result. Advice to retry unconditionally with a new `track()` call can create an event with a different ID. State this boundary in the collection API contract. Network retry idempotency does not resolve it.
- An installation ID identifies the source and does not replace the event idempotency key.
- For the first version's single collection space, a unique eventId is a candidate. Multiple projects require a combination of server-validated project scope and eventId. This review does not add multi-tenant features.
- Deleting event or deduplication records can allow old retries to be inserted again. Define the relationship between server retention and SDK retry/retention periods in the spec. Do not claim indefinite deduplication.
- Do not claim exactly-once transport. State the contract as prevention of duplicate storage and aggregation for the same ID within the retention scope.

## Cases to add to verification

1. Concurrent requests with the same eventId leave one DB row and one Dashboard event.
2. A retry after disconnecting the response after commit still leaves one event.
3. `[A, B, C]` followed by `[B, C, D]` leaves four unique events.
4. The same ID with different content produces an explicit conflict and preserves the original.
5. A response with some successes and some rejections updates only the corresponding IDs in the queue and preserves IDs with no result.
6. Persisted eventId values survive SDK restart.

## DB implementation evidence and cautions

On 2026-10-08, the main agent checked the [official PostgreSQL INSERT documentation](https://www.postgresql.org/docs/18/sql-insert.html). On a unique constraint conflict, `ON CONFLICT DO NOTHING` skips insertion of that row. `RETURNING` returns only rows actually inserted or updated, so it does not prove that a skipped row has identical content. The existing row must be checked. This supports an implementation candidate and is not a decision to adopt PostgreSQL. Implement and test against the selected DB's transaction isolation and concurrent insert behavior.

## Conclusions from vendor research

The [official documentation research](idempotency-research.md) by a separate `gpt-6.1-sol` subagent was reviewed. The main agent also checked the primary text for Segment, Amplitude, Mixpanel, and Stripe. The analytics examples use event identifiers for deduplication, but differ in namespace, duration, and storage/query guarantees. Do not generalize Stripe's request-result replay contract to all analytics systems.

For the first release, use a stable eventId, a DB unique constraint, and per-event responses after commit. This handles rebatching and reduces the work of retaining request cache keys, replaying responses, and managing expiry. It does not guarantee byte-identical response replay. The first response can be accepted and the retry response duplicate; the SDK treats both as successful receipt.

The recommendation is to prevent duplicates while server event rows remain. For the first demo, defer automatic server row expiry and document that the guarantee ends after explicit DB reset or deletion. This server retention proposal does not decide the deferred device queue limit in Q11. Adoption remains pending. No implementation or tests were performed.

## Independent Ponytail full review results

On 2026-10-08, an independent subagent read the installed skill and current documents. Its verdict was to retain the core structure and add the following details without new infrastructure. The main agent reviewed them and incorporated them into the design proposal.

- Do not add a request cache, Redis, separate aggregation counter, or general canonicalization framework in the first version. Compare fixed fields and parsed properties directly for content conflicts. Ignore object key order and preserve array order. Define numeric representation and null/missing comparison rules in the schema.
- A response keyed only by ID cannot identify errors for events with missing or invalid IDs. Add request-index mapping for rejection results only in this case. The SDK must interpret indexes against the immutable item list used to create that request. Do not map them to the current queue order or delete events on ambiguous responses.
- A unique conflict does not prove identical content. If committed existing content cannot be read or the transaction fails, retry instead of treating it as success. Verify isolation behavior after selecting the DB.
- Retain normal input validation, partial acceptance, WorkManager, failure diagnostics, and local transactions. Do not remove agreed data-loss prevention requirements on YAGNI grounds.
- The proposal to defer automatic server deletion does not create separate tombstones. Document that deduplication guarantees end after DB reset or deletion.

This verdict reviews design minimality. It is not runtime verification or a guarantee of implementation within 20~30 hours.
