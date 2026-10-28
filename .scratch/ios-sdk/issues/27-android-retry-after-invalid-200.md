# 27. Android: Retry-After on an invalid 200 ACK

Status: ready-for-agent
Type: task

## Scope and completion criteria

ADR-0006 decision 5 says a valid Retry-After applies to every row of the final-result table. The Android `Sender.applyResponse` passes `null` as Retry-After when a 200 response has an invalid ACK, so the header is dropped for that row. The iOS sender (ticket 09) follows the ADR.

1. In `sdk/sdk-android/src/main/kotlin/local/signaldock/internal/delivery/Sender.kt`, pass `retryAfter` (not `null`) to `fail(..., INVALID_ACK, ...)` in `applyResponse`.
2. A test in `DeliveryTest.kt`: a 200 with an invalid body and `Retry-After: 3600` stores a next attempt of at least now + 3600 s.
