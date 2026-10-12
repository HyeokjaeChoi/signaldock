# 21. Delivery failure E2E (part of issue 07)

Status: ready-for-agent
Type: task
Blocked by: None
Execution: Not started

## Scope and completion criteria

Spec section 7 rows "Normal E2E", "ACK loss", "Partial rejection", and "Content conflict", against the Docker Compose stack (issue 15) and a real SDK build.

- Normal E2E: demo `track()` → device queue → server storage → Dashboard confirmation.
- ACK loss: a fault proxy between the SDK and the server drops the response after the server commits. The SDK resends. Unique server rows and aggregates do not increase.
- Partial rejection: invalid events do not block valid-event storage; diagnostics and queue updates stay consistent.
- Content conflict: the same eventId with different content is rejected; the original row and its first receivedAt stay.

- Q119-Q124 (call and connect/read/write timeouts and their overrides, `retryOnConnectionFailure`, no redirects, bounded server wait with 503 and `Retry-After`): confirm each has a test against a real or mock server; add missing ones.

Done when each case runs, every Q119-Q124 item maps to a passing test, as a repeatable script or test, with commands, results, event ID comparisons, and environment recorded here.

## Comments

2026-10-12: Split from issue 07.
