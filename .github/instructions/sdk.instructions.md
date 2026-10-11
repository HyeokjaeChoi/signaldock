---
applyTo: "sdk/**"
---

# SDK review rules

### SDK delivery correctness (highest priority)
- `track()` success means the event is durably stored on-device. It is NOT server receipt. Never accept code that treats HTTP success as collection success or vice versa.
- Delete a queue entry only after an unambiguous successful-receipt ACK. On timeout, ambiguous, unparseable, or contradictory responses: apply nothing, preserve the whole batch, back off. Never delete based on failure counts or timeouts alone.
- Validate the full ACK against the immutable request-time list before applying: unknown statuses, duplicate indexes, ID mismatches, or omissions are NOT success.
- Retries reuse the same eventIds. Never generate a new eventId on retry, and never re-track solely because the caller's coroutine was cancelled.
- Propagate `CancellationException`; do not convert it into a normal failure result. Events already committed stay queued and send later.
- Deduplication is eventId plus content comparison (name, installationId, occurredAt, sdkVersion, properties). Same ID with different content is a conflict: preserve the existing row, permanently reject the newcomer. Never describe delivery as exactly-once.

### Input validation
- Properties are flat: strings, numbers, and booleans only. No nested objects, arrays, or nulls. Integers must be within ±(2^53−1); NaN and Infinity are errors.
- Reject, do not silently fix: duplicate DSL keys, empty or whitespace-padded names and keys (no auto-trim), NUL characters or lone surrogates. No Unicode normalization; compare code-point sequences exactly.

### Queue and scheduling
- Limits: 16 KiB per event, 256 KiB and 50 events per batch, 10,000 events and 16 MiB per queue, measured as actual UTF-8 JSON bytes. On overflow, preserve existing events and return QueueFull for new calls.
- Foreground and Worker share one sender and one lock. Never hold a DB transaction open across an HTTP call. Release the lock after each batch.
- Backoff: 10 second base doubling to a 5 minute cap, Full Jitter, one persisted next-eligible time shared by foreground and Worker. Server Retry-After wins. Reset the failure count only after a valid whole ACK is applied.

### Identity and privacy
- Installation ID is UUIDv4, generated once per installation and stored backup-excluded. It survives restarts and updates; data clearing, reinstallation, or restore to a new device starts fresh and may lose unsent events. Queue, diagnostics, and retry state are backup-excluded too. Never change the host app's backup settings.
- Logs default to WARN and ERROR. Never log event content, event names, installation IDs, credentials, or request bodies.
