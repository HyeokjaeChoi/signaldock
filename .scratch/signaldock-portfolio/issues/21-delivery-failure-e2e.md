# 21. Delivery failure E2E (part of issue 07)

Status: ready-for-agent
Type: task
Blocked by: None
Execution: Done (merged in PR #45, 2026-10-12)

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
| Q120 (10 s connect/read/write) | same defaults test (new); `Q120 - the read timeout ends a slow response even when the call timeout is long` (new); `Q120 - a connect that never completes ends at the connect timeout as TIMEOUT and the sender keeps the queue` (new; internal `SocketFactory` seam: the socket's `connect(address, timeout)` blocks for the timeout it is given and throws `SocketTimeoutException`; asserts the socket got 300 ms, `TIMEOUT`, queue kept; no network, no host-dependent backlog behavior); `Q120 - a write the peer never reads ends at the write timeout as TIMEOUT and the sender keeps the queue` (new; peer accepts and never reads a 64 MB body) |
| Q121 (host overrides, fixed for the lifetime, validation) | `Q121 - host overrides reach the client` (new); `LifecycleTest.invalid configuration fails without creating state`; `LifecycleTest.a different config is a conflict during the attempt and after success` |
| Q122 (`retryOnConnectionFailure=true`, state updated only by the sender) | `Q122 - retryOnConnectionFailure is on, and the SDK sender alone records the failure after the final transport result` (new); `Q122 - a retryable connection failure is retried inside the same Call, one ACK, no failure recorded` (new; behavioral: request 1 pools a connection, MockWebServer cuts it after receiving request 2, OkHttp retries on a new connection inside the same Call; 3 server requests, one ACK, `retryFailureCount=0`, no `lastDeliveryError`); `DeliveryTest.network, timeout, 408, 429 and 5xx keep the queue and are classified`. Evidence from case 2: a reset after the request was the final result of the Call |
| Q123 (no redirects, redirect is not an ACK, queue kept) | `redirects are not followed`; `Q123 - the client does not follow redirects, and a 302 keeps the queue under the configuration hold` (new); `DeliveryTest.configuration, contract and redirect responses hold the queue` |
| Q124 (server bounded wait gives 503 + positive Retry-After; SDK keeps the queue and honors it) | Server: `DatabaseTest.admission overload fails fast and releases the slot`, `IngestionIntegrationTest.route returns retryable 503 ErrorResponse with Retry-After`. SDK: `Q124 - a 503 with a positive Retry-After keeps the queue and the next attempt honors it` (new); `DeliveryTest.Retry-After longer than the backoff wins and a shorter one does not shorten it` |

**Limits.** Cases 1-3 ran on one emulator image (API 37, arm64); case 1 used the consumer app and, after the review fixes below, also the demo app (ADR-0010 item 2). Case 3 changes the name on the wire, so the rejected event differs from the stored one. Case 4 does not exercise the SDK. The Q122 in-Call recovery is covered by a unit test on a pooled connection; a fresh connection that fails is not retried by OkHttp (route failure), so the E2E case 2 reset stays the final result. The connect timeout test uses a fake socket (the SDK passes `connectTimeout` to `Socket.connect` and classifies the result), not a real stalled TCP connect. The proxy is cleartext on `10.0.2.2`. Not run in CI (needs an emulator). Not verified: physical devices, API 24, TLS.

2026-10-12 (review fixes, CodeRabbit Minor findings on PR #45):

- `scripts/e2e-delivery.sh`: the proxy readiness loop has a 10 s deadline and checks that `PROXY_PID` is alive; otherwise it exits 1 with the proxy's startup error. It passes `E2E_SERVER_PORT` and `E2E_DASHBOARD_PORT` (the ports it checked) to the cases, and builds and installs the demo app (`sdk/gradlew -p sdk :demo:app:assembleDebug -Psignaldock.endpoint=http://10.0.2.2:<proxy port> -Psignaldock.collectionKey=<key from .env>`).
- `scripts/e2e/e2e_cases.py`: ports and `SIGNALDOCK_*` values now come from the environment the entry script exported (no second `.env` parse, so quoted values work). Unknown case numbers exit 2 before anything runs. Case 3 starts over (up to 3 attempts) when the three taps are split across batches on a loaded emulator; this happened once in a run while another emulator was running. Case 1 now also drives the demo app: `pm clear`, open a product, tap "Add to cart", read the 8-character event IDs from the status card, wait for the matching new IDs on the server (exactly two new rows), check `GET /v1/events/{id}` through the Dashboard path for both, and compare by-name counts before and after. The consumer app part of case 1 stays. ADR-0010 item 2 and the README now say both apps are used and why: the demo app is the path the spec names; the consumer app tests the R8 build of the published AAR and shows the exact `diagnostics()` values that cases 2 and 3 need.
- `TransportTest.kt`: two behavior tests for Q120 (rows in the table above). Each runs in about 0.5 s. They use `connectTimeout` or `writeTimeout` of 300 ms with a 30 s `callTimeout`, assert the run ended in under 5 s (so the call timeout did not end it), `TIMEOUT` as `lastDeliveryError`, `queuedEvents=1`, and `retryFailureCount=1`.

Results (host: macOS, emulator `signaldock-api37` as `emulator-5556` with `ANDROID_SERIAL`; `signaldock-api24` of another agent ran in parallel at `emulator-5554` and was not touched):

- `sdk/gradlew -p sdk :sdk-android:testDebugUnitTest --no-daemon --rerun-tasks`: BUILD SUCCESSFUL.
- `scripts/verify-contract.sh`: ALL CONTRACT CHECKS PASSED.
- `scripts/e2e-delivery.sh` (full, with build): exit 0, cases 1 to 4 PASS. Demo part of case 1: `product_viewed` d5ed0014-c7fc-4e36-b78b-dd8ee717a73a and `add_to_cart` 40b17116-9188-4f4c-808a-fca5118fd510 were the only two new server rows; the Dashboard detail returned 200 for both; by-name counts 1 to 2 for each. (The first run of the new code also passed case 1 and failed case 3 for the batch split above, before the retry was added.)
- `scripts/e2e-delivery.sh 9` (rejected in the shell entry before any build; the Python side also rejects): prints `error: unknown case number '9'; known: 1 2 3 4` and exits 2 (no build ran). CodeRabbit's re-review found this late rejection as the only finding; fixed in a second commit.
- `E2E_SKIP_BUILD=1 E2E_PROXY_PORT=8080 scripts/e2e-delivery.sh 4` (port in use): the proxy's `Address already in use` error is shown and the script prints `fault proxy not ready` and exits 1 (the exit code itself was not captured; the output shows the `exit 1` branch).

Limit: the demo flow drives only product, then Add to cart (the demo screen shows only 8 characters of an event ID, so IDs are matched by prefix).

2026-10-12 (review round 2, CodeRabbit findings on PR #45):

- `TransportTest.kt`: the write-timeout listener binds explicitly to `127.0.0.1` (the SDK endpoint uses `127.0.0.1`; `getLoopbackAddress()` can be `::1` on Java 21). The connect-timeout test no longer fills an accept backlog (host-dependent: a full backlog can refuse instead of stall). It uses a test `SocketFactory` whose `connect(address, timeout)` blocks for the given timeout, then throws `SocketTimeoutException`; it asserts the socket received the configured 300 ms, the run took at least 250 ms and under 5 s, `TIMEOUT`, `queuedEvents=1`, `retryFailureCount=1`.
- Production seam (test only, default unchanged): `RetrofitBatchTransport(config, socketFactory: SocketFactory? = null)`; when non-null it is set on the `OkHttpClient`. ADR-0010 item 5 updated.
- New Q122 behavior test (row above). A first try with a failure on a fresh connection was not retried by OkHttp, so the test warms a pooled connection first.
- `scripts/e2e/e2e_cases.py` case 4: name counts for both names are read before the first POST; the check is now original name `+2` and conflicting name unchanged.
- Not run: the full e2e script (no emulator or Docker in this task).

2026-10-12: Merged into main in PR #45.

2026-10-12: Post-change case 4 run (after the review round 2 change to compare conflict counts with a baseline). From the repository root, Compose stack up, AVD `signaldock-api24` as the required device: `E2E_SKIP_BUILD=1 scripts/e2e-delivery.sh 4`: exit 0, "PASS case 4 content conflict"; aggregate e2e_conflict_24a68a22 +2 (original + sibling) from 0, conflicting name unchanged at 0; original row and Dashboard detail unchanged.
