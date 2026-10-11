---
status: accepted
---

# SDK delivery and recovery decisions (issue 04)

The design sources leave these points open (architecture "Retry and transport boundaries"; spec section 8 "A. Scheduling and failure recovery" and "B. Errors, ACKs, and data representation"). This ADR records the choices made in issue 04. It adds no new product behavior. Code is in `internal.delivery` (batching, HTTP, ACK application) and `internal.scheduling` (WorkManager, foreground triggers, backoff scheduling), as ADR-0005 requires.

## Decisions

1. **OkHttp pin: 5.4.0, not 5.5.0.** The `okhttp-android` 5.5.0 AAR declares `minCompileSdk=37` (read from `META-INF/com/android/build/gradle/aar-metadata.properties` in the Gradle cache). The SDK builds at compileSdk 36 (TOOLCHAIN.md), and AGP stops the unit-test build with `checkDebugUnitTestAarMetadata`. Every consumer app would also have to compile against API 37. OkHttp 5.4.0 passes the same check at compileSdk 36. Retrofit stays at 3.0.0 and WorkManager at 2.12.0. `mockwebserver3` uses the OkHttp version. Revisit when the project moves to compileSdk 37 (needs the API 37 platform and a Robolectric check).
2. **Batch group = contract version + installation ID.** A request has one `SignalDock-Contract-Version` header and one `installationId` (ADR-0001: delivery must not replace the stored ID). The sender takes the oldest event, then up to the batch limit of events with the same contract version and installation ID, oldest first. The group of the oldest remaining event goes next, so no group starves.
3. **The request body is built from the stored event JSON.** The sender concatenates the stored `payloadJson` values inside the generated wrapper form. A resend therefore carries the exact stored bytes. The size is `prefix + events + commas + suffix`, and the sender checks that the finished body has exactly this size. A test checks that the body equals what the generated `BatchIngestRequest` serializer produces, and that its size equals `batchRequestBytes`, the canonical function in `internal.transport`. Limits: 50 events, 256 KiB. A batch always holds at least one event.
4. **Room schema stays at version 1; two columns were added to `retry_state`.** `hold` (normal sending is stopped) and `maxBatchEvents` (batch cap after a 413 split). ADR-0003 item 7 applies: no SDK is released, so no migration. The exported schema `sdk/sdk-android/schemas/` is updated. A debug install that has an older database file must be cleared. `lastDeliveryError` stays as history after recovery. `hold` is the explicit state; `lastAck` and `lastDeliveryError` times show which one is newer.
5. **Final-result classification.**

   | Final result | Action |
   |---|---|
   | 200 with a valid ACK | Apply it (decision 7) |
   | 200 with an invalid ACK; 1xx; 2xx other than 200 | `INVALID_ACK`: apply nothing, count a failure, back off (Q59) |
   | Network error, call timeout, 408 | Count a failure, back off (`NETWORK`, `TIMEOUT`) |
   | 429 | Count a failure, back off (`RATE_LIMITED`) |
   | 5xx except 501 and 505 | Count a failure, back off (`SERVER_ERROR`) |
   | 400 | Hold (`CONTRACT`) |
   | 401, 403, 404, 415, other 4xx, 3xx, 501, 505 | Hold (`AUTH_OR_CONFIGURATION`) |
   | 413, more than one event | Split (decision 6) |
   | 413, one event | Hold (`PAYLOAD_TOO_LARGE`) |

   A valid Retry-After (delta-seconds or an HTTP date, positive) applies to every row. It is never capped. Time arithmetic saturates, so a huge value cannot overflow into the past.
6. **413 split.** The batch cap becomes half of the failed batch (at least 1) and is stored in `retry_state.maxBatchEvents`. A split does not count as a temporary failure. If Retry-After is valid, no request goes out before it, and the smaller cap survives a restart. After each valid ACK the cap doubles; it is cleared when it reaches 50. A server limit lower than 50 events therefore costs one extra 413 after each recovery, and every cycle still makes progress. A 413 for a batch of one event is a hold with cap 1: the recheck sends that single event.
7. **ACK application is one Room transaction** (`QueueDao.applyAck`): delete the settled events (accepted, duplicate and rejected), insert the rejection diagnostics (no payload, no name; code cut to 64 characters, `UNSPECIFIED` if the server sent none) and trim them to 100, and write the retry state with failure count 0, no next attempt, hold off and the ACK counts. A test makes the retry state write fail and checks that nothing else changed. ACK validation, besides the architecture rules: `accepted` and `duplicate` must echo the request `eventId`; a `rejected` item may omit it but must not contradict it; the known fields must have JSON types (a quoted `"index":"1"` is invalid, because kotlinx.serialization accepts it as an Int); extra fields are ignored (Q68). The ACK body is read only for status 200 and only up to 1 MiB.
8. **Hold.** A hold sets `nextAttemptAtMillis = now + max(30 minutes, Retry-After)`. After that time one batch is sent (cap 1 after a single-event 413). A valid ACK clears the hold and normal sending continues at once. A temporary failure during a hold also waits at least 30 minutes. A WARN is logged on entering and leaving a hold, not on each recheck.
9. **Backoff.** On a temporary failure the sender stores `failureCount + 1`, draws `U(0, B)` once (B = 10 s, doubling, cap 5 minutes; the draw is clamped to B), and stores `next = now + max(draw, Retry-After)`. A run ends after any failure, so a draw of 0 cannot start a tight loop; the next run is scheduled for the stored time. The failure count is reset only in the ACK transaction (Q75).
10. **One sender, one Mutex per process.** `Sender` selects, sends, validates and applies one batch under a `Mutex`; the lock is released between batches. The foreground trigger and the Worker both call it through `DeliveryCoordinator`. The Mutex does not cover a second process. The design has one SDK in the default process (spec section 8.C, Q86; architecture "Decisions and tradeoffs": serialization is limited to one process). If a host app runs the SDK in two processes, both can send the same events. The server then answers `duplicate`, so this is safe but wasteful. No cross-process lease was added.
11. **Scheduling.**
    - Unique one-time work (name `local.signaldock.delivery`, network required, policy `APPEND_OR_REPLACE`) and unique periodic work (15 minutes, `KEEP`). `APPEND_OR_REPLACE` makes a request made while a Worker runs wait for it; it never cancels a running Worker. The host's WorkManager configuration is not changed. If WorkManager is not initialized, the SDK logs a WARN and foreground delivery still works.
    - A request for a time at or after an already enqueued, not yet started request is skipped (in-memory marker, cleared when a Worker starts). Sending is not possible before the stored next-attempt time anyway.
    - Scheduling is checked at initialization (now if the restored queue is not empty, otherwise after the foreground delay), after each stored event (at now + 10 s, as a fallback if the process ends before the foreground trigger), after a run that ends with a wait (at the stored time) or with an exhausted budget (now), and when the last started activity stops (`ActivityLifecycleCallbacks`; no `lifecycle-process` dependency).
    - The Worker always returns success. It never sleeps and never uses the WorkManager backoff, because the SDK's own persisted time is the one retry clock. Its budget is 10 batches or 30 seconds, checked between batches (Q72). After process recreation it waits up to 15 seconds for the host's `initialize` call and ends without HTTP if none comes; the periodic work tries again.
    - The foreground trigger starts a drain after 20 stored events, or 10 seconds after the first event stored since the last drain started. This approximates "10 seconds after the oldest pending event" without a query per `track`. Only one drain runs at a time; requests during a drain cause one more drain. A drain does not check the retry time itself; the sender refuses to send before it.
12. **Transport.** Retrofit 3.0.0 over OkHttp: `retryOnConnectionFailure=true`, no redirects (same-origin included), the four timeouts from the configuration, `Authorization: Bearer <key>`, `Content-Type: application/json` (the body is a byte array, so OkHttp adds no charset). No interceptor, no logging interceptor, no EventListener (Q122 supplement). Cancellation cancels the Call and leaves the queue unchanged.

## Not verified here

- R8 with Retrofit's reflection on the `IngestApi` interface. This belongs to the independent R8 consumer app (distribution issue).
- A real process restart, WorkManager execution on a device or emulator, Doze and constraint behavior, and `ActivityLifecycleCallbacks` with real activities. The JVM tests use Robolectric, `work-testing` and fakes.
- Status follow-ups inside one OkHttp Call (408, 503 with `Retry-After: 0`, HTTP/2 421) were not exercised. The tests cover only the final result.
