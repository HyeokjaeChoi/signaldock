# 21. Delivery failure E2E (part of issue 07)

Status: ready-for-agent
Type: task
Blocked by: None
Execution: In review

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

2026-10-12 (implementation, branch `impl/21-delivery-failure-e2e`):

**File map.** `scripts/e2e-delivery.sh` (entry), `scripts/e2e/fault-proxy.py` (test-only proxy, Python 3 stdlib), `scripts/e2e/e2e_cases.py` (the four cases), `docs/adr/0010-delivery-failure-e2e-harness.md` (decisions), `README.md` (run steps), `sdk/sdk-android/src/test/kotlin/local/signaldock/TransportTest.kt` (Q119-Q124 tests), `sdk/sdk-android/.../delivery/BatchTransport.kt` (product code: `client` is now `internal val`; no behavior change).

**Design followed.** architecture "Collection, delivery, and ACK", "Lost ACKs and event deduplication", "Retry and transport boundaries"; spec section 5, section 7 rows Normal E2E / ACK loss / Partial rejection / Content conflict, section 8.A and 8.B (Q59, Q61, Q68), Q119-Q124; ADR-0004, ADR-0006, ADR-0008, ADR-0009. New decisions: ADR-0010.

**Environment.** Emulator `signaldock-api37` (`sdk_gphone16k_arm64`, Android 17, API 37, arm64-v8a, `google/sdk_gphone16k_arm64/emu64a16k:17/CP41.260831.007/16416850:userdebug/dev-keys`). Consumer app `local.signaldock.consumer` 0.1.0, R8 release build from the unzipped Maven ZIP, endpoint `http://10.0.2.2:8081` (the fault proxy, forwarding to `127.0.0.1:8080`). Server: Compose `server` image built from this branch (Ktor, jOOQ 3.21, Java 21.0.12.1). DB: PostgreSQL 17.6 (alpine, arm64). Docker 29.4.0 (OrbStack). Python 3.9.6. The DB volume already held earlier events (13 rows before case 2 of the final run), so every check compares before and after.

**Commands and results.**

- `scripts/e2e-delivery.sh` (full run: builds the ZIP and the APK, installs, runs the cases): exit 0. Summary: `PASS case 1 normal E2E`, `PASS case 2 ACK loss`, `PASS case 3 partial rejection`, `PASS case 4 content conflict`. Earlier runs on the same day failed twice because of harness bugs (a `.env` `PORT` variable overriding the proxy port; a row-parsing bug; case 3 taps slower than the 10 s foreground window). All were fixed in the scripts; no product behavior was involved.
- `scripts/verify-contract.sh`: ALL CONTRACT CHECKS PASSED.
- `(cd server && ./gradlew test verifyJooq --no-daemon)`: BUILD SUCCESSFUL.
- `sdk/gradlew -p sdk :sdk-android:testDebugUnitTest --no-daemon --rerun-tasks`: BUILD SUCCESSFUL.

**Evidence (final run).**

- Case 1: `track()` on the device returned eventId `a0d4b640-d1ac-4b8d-b71e-1163e19f943b`. Device `lastAck` accepted=1, queued=0. The server stored exactly that new ID. Dashboard path `GET 127.0.0.1:5173/v1/events/{id}` with `SignalDock-Contract-Version: 1` returned 200, name `consumer_ping`, receivedAt `2026-10-12T06:40:36.347Z`. The list with an `eventId` filter returned it, and by-name `consumer_ping` went 10 to 11.
- Case 2: eventId `165a5277-d61d-4494-93f7-9d71a1c89e61`. Proxy mode `drop-response`. The server committed (rows 13 to 14, by-name count 11 to 12, receivedAt `2026-10-12 06:40:55.98+00`); the proxy dropped the response (`accepted`). Device: queued=1, `lastDeliveryError=NETWORK`, retryFailureCount=2, no lastAck. The SDK resent while the response was still lost (proxy log: second forward answered `duplicate`, dropped); rows and receivedAt unchanged. After the proxy passed: device queued=0, lastAck (accepted, duplicate, rejected) = (0, 1, 0), retryFailureCount=0. Event ID set before the resend (14) equals the set after (14); unique rows 14 to 14, Dashboard aggregate 12 to 12, first receivedAt unchanged, Dashboard detail shows the first receivedAt.
- Case 3: three taps, one batch `[3186c923-..., 2e51128c-..., de90f02a-...]`; the proxy changed only the name of index 1 (leading space). Server ACK: accepted, rejected `INVALID_TEXT`, accepted. Server stored `3186c923-...` and `de90f02a-...` and not `2e51128c-...`. Device: queued=0, lastAck (2, 0, 1), `recentRejections=[(2e51128c-..., INVALID_TEXT)]`, retryFailureCount=0.
- Case 4: original `b0dc285f-a212-4a29-aeba-f2cbeb716a33` stored (receivedAt `2026-10-12 06:41:35.091+00`). Same content with another key order and `100.0` returned `duplicate`. Different name returned `CONFLICT` and the valid sibling in the same batch was accepted. Different properties returned `CONFLICT`. The original row (name, receivedAt, installationId, properties) and the Dashboard detail stayed unchanged; rows added were the original and the sibling only.

**Q119-Q124 to tests** (all pass in `testDebugUnitTest` and `./gradlew test`; SDK tests are in `local.signaldock.TransportTest` unless noted).

| Q | Test |
| --- | --- |
| Q119 (30 s callTimeout) | `Q119 Q120 - defaults are a 30 s call timeout and 10 s connect, read and write timeouts, applied to the client` (new); `Q119 - the whole-call timeout ends a slow response even when the read timeout is long` (new); `a response that does not arrive in time is a TIMEOUT` |
| Q120 (10 s connect/read/write) | same defaults test (new); `Q120 - the read timeout ends a slow response even when the call timeout is long` (new). Connect and write timeouts are asserted on the client only; no behavioral test |
| Q121 (host overrides, fixed for the lifetime, validation) | `Q121 - host overrides reach the client` (new); `LifecycleTest.invalid configuration fails without creating state`; `LifecycleTest.a different config is a conflict during the attempt and after success` |
| Q122 (`retryOnConnectionFailure=true`, state updated only by the sender) | `Q122 - retryOnConnectionFailure is on, and the SDK sender alone records the failure after the final transport result` (new); `DeliveryTest.network, timeout, 408, 429 and 5xx keep the queue and are classified`. Evidence from case 2: a reset after the request was the final result of the Call |
| Q123 (no redirects, redirect is not an ACK, queue kept) | `redirects are not followed`; `Q123 - the client does not follow redirects, and a 302 keeps the queue under the configuration hold` (new); `DeliveryTest.configuration, contract and redirect responses hold the queue` |
| Q124 (server bounded wait gives 503 + positive Retry-After; SDK keeps the queue and honors it) | Server: `DatabaseTest.admission overload fails fast and releases the slot`, `IngestionIntegrationTest.route returns retryable 503 ErrorResponse with Retry-After`. SDK: `Q124 - a 503 with a positive Retry-After keeps the queue and the next attempt honors it` (new); `DeliveryTest.Retry-After longer than the backoff wins and a shorter one does not shorten it` |

**Limits.** Cases 1-3 ran on one emulator image (API 37, arm64) with the consumer app, not the demo app (ADR-0010 item 2). Case 3 changes the name on the wire, so the rejected event differs from the stored one. Case 4 does not exercise the SDK. The Q122 in-Call connection recovery of OkHttp itself is not triggered by a test (only the setting is asserted); Q120 connect/write timeouts have no behavioral test. The proxy is cleartext on `10.0.2.2`. Not run in CI (needs an emulator). Not verified: physical devices, API 24, TLS.
