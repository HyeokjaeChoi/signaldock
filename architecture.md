# SignalDock architecture

The SDK stores events on the device and retries with the same `eventId`. It removes acknowledged events only after the server commits them and the SDK validates the ACK.

> **Design baseline: 2026-10-09 (Q127 approved). Updated for SDK 0.3.0: 2026-10-21 UTC.**
> The product is implemented. The behavior below is the agreed design and the built behavior. Open items: no process-boundary script run, no release (R8) or Baseline Profile run on a physical device (one phone ran the instrumented tests, the debug device measurement, and the host-app cost with SDK 0.3.0, [issue 85](.scratch/signaldock/issues/85-device-verification-v0.3.0.md); the instrumented tests also ran with 0.2.0 code, [issue 73](.scratch/signaldock/issues/73-real-device-test-galaxy-z-flip7.md)), and the 216-run server matrix was not run (see [Independent distribution and verification](#independent-distribution-and-verification)).
> See the [spec](.scratch/signaldock/spec.md) for the full contract and the [ADRs](docs/adr/) for the decisions.

## Components and responsibilities

![SignalDock architecture: demo app to public SDK API, Room queue, shared sender, Ktor, and PostgreSQL. WorkManager schedules the sender; the Dashboard queries Ktor.](docs/architecture/signaldock-architecture.svg)

The SDK is the main product. The demo app and Dashboard help verify its behavior.

| Component | Responsibility and boundary |
| --- | --- |
| Host app / Compose demo | Collect business events explicitly after initialization. Do not collect again automatically after recomposition or call cancellation |
| Public SDK API | Validate input, return collection results, and expose read-only diagnostics. Do not expose shopping models or transport DTOs |
| Room / sender | Own the durable queue, capacity checks, batches, ACK application, and retry state shared by foreground work and WorkManager |
| Ktor / PostgreSQL | Validate contracts and the collection key (ingestion) or query key (queries); store and deduplicate events; ACK after commit; expose query APIs |
| React Dashboard | Show server records, details, and supporting counts. Access PostgreSQL only through Ktor |

App diagnostics are a snapshot of the device queue, recent ACK, and next retry.
The Dashboard shows server records. Those records cannot establish the device queue state or retry count.
The SDK has an opt-out switch: `setOptOut`, `isOptedOut`, and `clearQueue`. While opted out, `track()` returns `TrackResult.OptedOut` and stores nothing. Queued events stay and the sender still sends them. A host app that must stop sending calls `clearQueue()` ([ADR-0034](docs/adr/0034-sdk-opt-out.md)).
The collection key checks access for local evaluation. An SDK key can be extracted and does not provide user authentication, so the collection key is write-only. A separate query key guards the Dashboard read endpoints and is added by a same-origin proxy (spec Q128 to Q131, ADR-0007).

## Collection, delivery, and ACK

![Normal sequence: track returns collection success after Room commit. The sender calls Ktor, receives an ACK after PostgreSQL commit, validates it, and applies it to the Room queue.](docs/architecture/signaldock-sequence.svg)

### Commit boundaries

1. Room collection commit: return collection success after validation and atomic storage. A server connection is not required.
2. Server commit: store valid events in PostgreSQL, then return per-event ACK results. This establishes server receipt. With an optional tracking plan, each new row also gets a plan status (`planned`, `unplanned_event`, `invalid_properties`). The status only marks the row; it never changes storage or the ACK ([ADR-0022](docs/adr/0022-server-tracking-plan-status.md)).
3. Room ACK commit: validate the entire ACK and commit queue changes. This records the earlier receipt on the device.

The SDK also counts the events it drops, by reason, in Room. After a send cycle that applied a valid ACK, it sends the counts once to `POST /v1/client-reports`. The report is separate from the batch contract and from the retry state; a permanent result (for example 404, other 4xx, 501, 505, or a redirect) discards it, a transient failure (network error, 408, 429, other 5xx) keeps the counters (`ClientReport.kt`, `classifyReport`) ([ADR-0036](docs/adr/0036-sdk-client-reports.md)).

The sender checks the earliest retry time, selects an immutable batch, sends HTTP, validates the entire ACK, and applies it locally.
Foreground work and the Worker serialize this sequence with the same Mutex. Release the lock after each batch and check cancellation and execution conditions.
New `track()` writes use a separate path from the send lock. Hold no Room transaction open during HTTP.

### Validate the entire ACK before changing the queue

Compare the full response with the immutable request list.
Each index, eventId, count, and status must match. Unknown statuses, duplicate indices, mismatched IDs, missing results, or contradictions invalidate the ACK.

| ACK result | Device action |
| --- | --- |
| `accepted` / `duplicate` | Remove the corresponding queue item |
| Known `rejected` | In one transaction, store a recent diagnostic without the event payload and remove the item |
| Unreadable, missing, or contradictory | Apply none of the response. Keep the entire batch; record a protocol diagnostic and apply backoff |

Reset the retry failure count only after the entire valid ACK has been applied locally.
This also applies when every result is a known permanent rejection. A 2xx response or Call completion alone does not reset the count.

## Lost ACKs and event deduplication

If the server commits but the response is lost, the SDK retains its queue items.
It retries with the same IDs and content. The server returns `duplicate` and preserves the first `receivedAt`.
The SDK applies the result after it validates the entire ACK. A timeout does not prove that the server stored nothing.

| Comparison | Server result |
| --- | --- |
| Same ID and content | Duplicate success, with no extra row or count |
| Same ID, different content | Permanent `conflict` rejection; preserve the original row |
| Same ID and content repeated within a batch | Store one row and return a result for each request index |
| Same ID with different content within a batch | Reject the entire group for that ID; process other IDs normally |

The `eventId` unique constraint controls concurrent inserts. On conflict, compare against the committed existing row; a lookup before insertion is insufficient.
Compare name, installationId, occurredAt, sdkVersion, and properties. Exclude receivedAt, batch composition, and request time.
JSON key order has no meaning. Bytes or hashes alone do not establish content equality.
Compare validated numbers by value: `1`, `1.0`, and `1e0` are equal; the string `"1"` is different. Do not use approximate comparison.

Keep eventId through batch splitting and regrouping. The first release has no separate batch Idempotency-Key, response cache, or Redis.
It also has no automatic server TTL or deduplication tombstone. Deduplication of storage and counts lasts while the row remains.
After row deletion or DB reset, an old event can be stored again. The design does not guarantee exactly-once delivery.

## SDK lifecycle and cancellation

Use one SDK instance with one fixed configuration in the default process. Retain only `applicationContext`.
Separate the public API, storage, delivery, scheduling, and diagnostics within one published module. Connect internal dependencies with manual DI.
The SDK has no Compose / ViewModel dependency. The public API signatures are recorded in `sdk/sdk-android/api/sdk-android.api`; CI checks them ([ADR-0019](docs/adr/0019-sdk-public-api-check.md)).

- Call `track()` after suspend initialization succeeds and returns the SDK instance. Prepare storage off the main thread.
- Concurrent initialization calls with the same configuration share one attempt and result. Reuse a successful instance; reject a different configuration.
- Retry failed initialization through a new explicit host call. Preserve the existing queue and installation ID.
- A caller can cancel its own wait. Shared initialization continues in the SDK's Application / process scope, with no completion guarantee after process death.
- Propagate `CancellationException` from collection. An event may already have committed; collecting the same action again can create a second event with a new ID.
- Store eventId, installationId, occurredAt, the sdkVersion at collection, and the contract version. Preserve them through updates and retries.

The SDK requires an `https` endpoint. An `http` endpoint needs `SignalDockConfig.allowInsecureEndpoint = true`. The SDK never upgrades `http` to `https` ([ADR-0035](docs/adr/0035-sdk-https-enforcement.md)).
If the server sends `Deprecation` or `Sunset` headers, the SDK writes one WARN per process and exposes `Diagnostics.contractDeprecation`. Delivery does not change ([ADR-0030](docs/adr/0030-sdk-contract-deprecation-warning.md)).

Logcat defaults to WARN / ERROR. Even at DEBUG, omit event payloads, event names, installation IDs, credentials, and HTTP bodies.
Start with diagnostics for send start, final result, elapsed time, and next retry. Detailed EventListener instrumentation is deferred.

## Retry and transport boundaries

| Layer | Allowed behavior |
| --- | --- |
| Inside one OkHttp Call | `retryOnConnectionFailure=true`. Allow connection recovery and limited status follow-ups |
| SDK after the final result | Classify the result and persist the next attempt time. After the wait, create a new Call with the same eventId |
| Redirects | Disable automatic redirects, including within the same origin. Do not change the endpoint from Location |
| Server | No separate automatic retry loop for transient DB failures |

Internal Call callbacks do not each increment the SDK failure count. SDK cooldown does not necessarily apply to each intermediate HTTP response.
Queue retention and Retry-After rules apply to the final result. The SDK reads `Deprecation` and `Sunset` headers from any HTTP response, whatever the status, and does not change the status handling ([ADR-0030](docs/adr/0030-sdk-contract-deprecation-warning.md)).

| Final failure | SDK policy |
| --- | --- |
| Network / timeout / 408 / 429 / transient 5xx | Keep the queue and persist backoff |
| Authentication, configuration, or contract error / redirect | Pause normal delivery. Check recovery with at most one batch every 30 minutes; send no HTTP request if the queue is empty |
| 413 with multiple events | Split into smaller batches and keep IDs. Do not retry before Retry-After |
| 413 with one event | Diagnose a size contract mismatch, keep the entire queue, and pause normal delivery. Check recovery with that request every 30 minutes |

Backoff base B starts at 10 seconds for the first failure, doubles, and caps at 5 minutes. Full Jitter `U(0, B)` can produce a retry with almost no wait.
Choose and persist the next attempt time once per failure. Honor a longer valid Retry-After and OS constraints, including during recovery checks.
Foreground work and the Worker share this state. Process restart and new triggers do not bypass the wait. Failure count alone never discards an event.

### Defaults

| Item | Value and meaning |
| --- | --- |
| Event / batch | Event: 16 KiB maximum. Entire batch: 256 KiB / 50 events. Measure uncompressed UTF-8 JSON, including escaping and wrappers |
| Queue | At most 10,000 events or 16 MiB of transport JSON in total. Check limits and insert atomically. Keep existing events and return QueueFull for new calls at capacity |
| Foreground | Trigger at 20 events or 10 seconds after storage of the oldest pending event. Honor retry waits |
| Worker | Unique one-time work plus 15-minute periodic recovery. Stop starting batches at 10 batches or a 30-second soft budget |
| HTTP | Call: 30 seconds; connect / read / write: 10 seconds each. The host can set valid positive finite values at initialization |

The queue budget does not cap the Room file size. Measure indices, journals, and sidecars separately. There is no queue TTL.
WorkManager does not promise an exact execution time. The soft budget does not require termination within exactly 30 seconds.
Read / write timeouts limit individual I/O waits. HTTP timeouts and the Worker budget have separate boundaries.

## Contracts, generated sources, and JSON

[ADR-0002](docs/adr/0002-openapi-json-contract.md) selects OpenAPI sources and JSON for collection and queries.
Generate Kotlin models for the SDK / Ktor and TypeScript models for the Dashboard. Keep public API types, Room entities, jOOQ records, and DTOs separate by role.

- Require `SignalDock-Contract-Version: 1` on business APIs. Contract and SDK release versions are separate. The first release implements only contract 1.
- Send queued events under their stored original contract, in batches grouped by contract. Support for old contracts is not indefinite.
- Reject a missing header with HTTP 400. Reject the entire request for an unsupported contract or unknown request contract field, without automatic downgrade. Free properties keys are exempt.
- Ignore compatible extra response fields. Unknown ACK statuses, missing results, and contradictions still invalidate the entire ACK.
- Properties accept flat string / number / boolean values. Reject arrays, nested objects, explicit null, duplicate keys, and leading or trailing whitespace in names or keys. Do not silently correct input.
- Integers must fit ±(2^53−1); decimals must be finite Double values. Apply the integer limit through the Double path too. Reject NaN / Infinity. Use strings for large IDs and integers in the smallest currency unit for exact amounts within range.
- Compare text without Unicode normalization. Reject NUL and lone surrogates. Use UTC RFC 3339 timestamps with milliseconds. The server accepts valid offsets but does not truncate excess precision.

Track generated OpenAPI Kotlin / TypeScript and jOOQ sources in Git. Do not edit them directly.
CI regenerates into an empty temporary output with fixed inputs and tools, then checks added, deleted, and changed files (`scripts/verify-contract.sh`, job `verify-contract`).
Normal compilation must not overwrite generated sources. Keep schema diffs, earlier JSON fixtures, compilation, and storage/ACK integration checks.
Verify scalar adapters and generator compatibility during implementation. Generated types that compile do not prove input validation or release compatibility.

## DB time budgets and queries

JDBC blocks. Bound concurrency in an IO execution area from connection acquisition through transaction completion and connection return.
Design admission and connection waits, SQL, commit and response, and error cleanup within one request budget.
If admission cannot complete within the limit, return HTTP 503 with a positive Retry-After.

Set `lock_timeout < statement_timeout` and apply limits in the transaction on the same connection.
Statement timeout does not set a deadline for the whole transaction. A timeout response proves neither absence of storage nor rollback or connection recovery.
Verify rollback or connection disposal after errors, setting leaks on pool reuse, and the cancellation/commit boundary separately.
Normal runtime values are undecided. Do not copy the [benchmark profile](.scratch/signaldock/performance-plan.md) into normal runtime settings automatically.

Apply Flyway migrations to a temporary PostgreSQL schema, generate jOOQ sources, then compile the server.
Do not reset the runtime DB for codegen. Add new migrations instead of editing applied migrations.
Store common fields in typed columns and properties in JSONB. Query with COUNT / GROUP BY; use no separate counter or aggregation Worker.

| Query boundary | Design |
| --- | --- |
| List | Newest-first receivedAt / eventId cursor. Default 50, maximum 100 records; exact name / installationId / eventId filters and an optional plan status filter |
| Period / buckets | Initial project timezone: Asia/Seoul. [start, end), at most 30 calendar dates. At most 720 hourly / daily buckets |
| Refresh | Poll every 5 seconds while visible; stop while hidden and prevent overlapping identical requests. Keep past pages and details in place |
| Errors | Transient failures use 5→10→20→30-second backoff and a longer Retry-After. Explicit contract errors stop polling |

The cursor is not a snapshot, commit order, or change stream. One traversal may miss concurrent inserts or late commits.
List and aggregate responses can reflect different times. Stored event JSON is not the original HTTP bytes, and the timestamp difference is not pure network latency.

## Installation lifecycle and durability

[ADR-0001](docs/adr/0001-installation-scoped-identity.md) uses a UUIDv4 installation ID in SDK-owned `noBackupFilesDir` storage.
Exclude the queue, diagnostics, retry state, and DB sidecars from backup too. Preserve the host app's other backup settings.
Keep SDK data across normal restarts and updates of the same installation. Do not carry it across data clearing, reinstallation, or supported device restores.
These transitions can lose unsent events. The design does not cover every manufacturer's transfer tool or arbitrary host restore.
A committed event survives process death, not power loss: the queue uses WAL with `synchronous=NORMAL` ([ADR-0029](docs/adr/0029-device-fault-tests-and-power-loss.md)).
Configure the server to preserve its DB volume across normal shutdown and restart. Treat DB reset as a separate operation.

## Independent distribution and verification

The artifact is a Maven repository ZIP with an AAR, POM, and metadata. It is a GitHub Release artifact (`scripts/release.sh`, `.github/workflows/release.yml`).
Coordinate: `local.signaldock:sdk-android:0.3.0`. Public Kotlin package: `local.signaldock`. Build it with `sdk/gradlew -p sdk :sdk-android:signaldockMavenZip` (see the README and [ADR-0009](docs/adr/0009-maven-zip-and-consumer-app.md)).
Install by coordinate in an independent Gradle consumer app; verify an R8 release build, execution, and server integration without an SDK source project dependency.
Android and server use separate Gradle builds. The Dashboard and consumer app also keep independent builds.
The [README](README.md) documents the entry point. Reproduction that includes initial dependency downloads is not fully offline.

| Evidence | Required result | Status |
| --- | --- | --- |
| Collection / recovery | Normal E2E; preserve IDs and content for 1,000 offline events through process restart | Done: issues [20](.scratch/signaldock/issues/20-sdk-durability-device.md), [21](.scratch/signaldock/issues/21-delivery-failure-e2e.md) |
| ACK / deduplication | ACK loss after commit, duplicate / conflict, partial rejection, entire batch retained for ambiguous ACKs | Done: issue [21](.scratch/signaldock/issues/21-delivery-failure-e2e.md) |
| Concurrency / lifecycle | Sender contention, cancellation, capacity and initialization races, updates, reinstallation, sidecar backup exclusion | Done on emulators: issues [20](.scratch/signaldock/issues/20-sdk-durability-device.md), [33](.scratch/signaldock/issues/33-sdk-crash-safety-and-release-build.md); CI device tests: issue [40](.scratch/signaldock/issues/40-ci-device-tests-and-size-budget.md); instrumented tests on one physical device (Galaxy Z Flip7): issue [73](.scratch/signaldock/issues/73-real-device-test-galaxy-z-flip7.md) (Android 16, 0.2.0 code) and issue [85](.scratch/signaldock/issues/85-device-verification-v0.3.0.md) (Android 17, SDK 0.3.0). Gap: `scripts/device-durability.sh` was not run on a physical device |
| DB resources | Locks, slow SQL, pool exhaustion, cancellation, rollback, connection reuse, and uncertain commits | Done: issues [22](.scratch/signaldock/issues/22-contract-db-change-checks.md), [23](.scratch/signaldock/issues/23-server-load-measurement.md) (one overload run). Gap: no deterministic test for a cancel during `COMMIT` |
| Contracts / distribution | Scalar round trips, schema diffs, fixtures, generated source drift, migrations, independent Maven ZIP installation, and R8 | Done: issues [22](.scratch/signaldock/issues/22-contract-db-change-checks.md), [06](.scratch/signaldock/issues/06-distribution.md) |
| Android performance | Track p50 / p95, actual injection rate, queue drain, DB / sidecar size, and memory | Done on emulators: issues [24](.scratch/signaldock/issues/24-sdk-device-measurement.md), [36](.scratch/signaldock/issues/36-sdk-host-app-cost.md). On one physical device (debug build, SDK 0.3.0): issue [85](.scratch/signaldock/issues/85-device-verification-v0.3.0.md). Gap: no release (R8) or Baseline Profile measurement on a physical device |
| Server performance | Collection and query HTTP latency; ID comparisons, errors, resources, and pool 2 / 4 / 8 comparison | Reduced (Q133): two pilots and one overload run, no pool comparison ([issue 23](.scratch/signaldock/issues/23-server-load-measurement.md), [ADR-0013](docs/adr/0013-server-load-measurement-harness.md)) |

The [performance plan](.scratch/signaldock/performance-plan.md) baseline has 24 conditions × 3 pool sizes × 3 runs = 216 runs. The 216-run matrix was not run (Q133, [ADR-0013](docs/adr/0013-server-load-measurement-harness.md)).
Each run has 1 minute of warmup and 5 minutes of measurement: at least 21 hours 36 minutes of automated execution, excluding setup and recovery.
100 events/s and collection ACK p95 ≤ 500 ms are injection and acceptance targets. Direct HTTP load and SDK performance need separate results.
Record the environment, source revision/hash, fixtures, actual request rate, errors, and eventId comparisons. Performance figures do not compensate for correctness failures.

## Decisions and tradeoffs

| Choice | Cost | Source |
| --- | --- | --- |
| Installation identity and SDK backup exclusion | Lose continuity and unsent data across reinstallation and restore | [ADR-0001](docs/adr/0001-installation-scoped-identity.md) |
| OpenAPI / JSON with generated sources in Git | Maintain generation paths, drift checks, and scalar validation | [ADR-0002](docs/adr/0002-openapi-json-contract.md) |
| Event deduplication and a shared sender | Deduplication depends on row retention; serialization is limited to one process | [Spec](.scratch/signaldock/spec.md) |
| SDK health report of dropped events | Counts are approximate (no idempotency, drops that cannot be stored are lost); one more small request per send cycle with drops | [ADR-0036](docs/adr/0036-sdk-client-reports.md) |
| SDK opt-out and `clearQueue` | Queued events still send after opt-out; a race can store one event after `setOptOut(true)` | [ADR-0034](docs/adr/0034-sdk-opt-out.md) |
| HTTPS required by default | Host apps and demo apps set `allowInsecureEndpoint` for local `http` | [ADR-0035](docs/adr/0035-sdk-https-enforcement.md) |
| Deprecation / Sunset headers read by the SDK | The SDK warns only; the server does not send the headers yet | [ADR-0030](docs/adr/0030-sdk-contract-deprecation-warning.md) |
| Dependency baseline for the published SDK | A dependency change needs a reviewed baseline update | [ADR-0033](docs/adr/0033-sdk-dependency-baseline.md) |
| Server-side collection kill switch (block by event name or by (platform, SDK version); shed ingest with 503) | The server cannot stop collection on the device; a blocked event still costs one request; a change needs a restart | [ADR-0037](docs/adr/0037-server-collection-kill-switch.md) |
| Gradle dependency verification with SHA-256 checksums | Checksums are trust on first use; each Renovate Gradle PR needs `scripts/update-gradle-verification.sh` run by hand | [ADR-0038](docs/adr/0038-gradle-dependency-verification.md) |
| Build provenance attestation for the release ZIP | Two more permissions and one more third-party action on the `publish` job; it proves the builder, not that the code is safe | [ADR-0039](docs/adr/0039-release-provenance-attestation.md) |
| Baseline Profile shipped in the SDK AAR | Regenerate by hand on an emulator after hot-path changes; the gain is not proved on the emulator | [ADR-0040](docs/adr/0040-sdk-baseline-profile.md) |
| Claude Code reviews each PR; CodeRabbit removed | One Opus run per push on the owner's subscription; one reviewer instead of two; no review on fork PRs | [ADR-0041](docs/adr/0041-claude-code-pr-review.md) |
| Measurement scripts run on a personal phone only with an opt-in (debug build) | Phone numbers are not comparable with emulator numbers; test packages stay installed; durability script stays emulator-only | [ADR-0042](docs/adr/0042-measurement-on-a-physical-device.md) |
| Cloud demo on one GCP VM with the Compose stack, tag deploy by pull, teardown on 2027-01-16 UTC (deployed 2026-10-25 UTC, tag `demo-v0.1.1`; not-run checks in `.scratch/cloud-demo/issues/05-tag-deploy-and-daily-reset.md` and `07-detection-and-scans.md`) | One VM is one point of failure; public GHCR images; no managed services | [ADR-0043](docs/adr/0043-cloud-demo-hosting-and-deploy.md) |
| Cloud demo: host nginx limits, basic auth, daily reset, and access log replace the ADR-0028 rows (deployed 2026-10-25 UTC, tag `demo-v0.1.1`; not-run checks in issues 05 and 07) | Shared keys, no audit trail, no per-user login, data lost at reset; the server code does not change | [ADR-0044](docs/adr/0044-cloud-demo-operation-replaces-adr-0028.md) |
| Cloud demo: container and host hardening (deployed 2026-10-25 UTC, tag `demo-v0.1.1`; not-run checks in issues 05 and 07; docker-bench findings in `.scratch/cloud-demo/issues/14-docker-bench-findings.md`) | Read-only containers can break at start; thin detection; automatic OS upgrades can restart services | [ADR-0045](docs/adr/0045-cloud-demo-host-and-container-hardening.md) |
| Server marks events against an optional tracking plan | The status is fixed at ingest; old rows and no-plan servers have none; no hot reload | [ADR-0022](docs/adr/0022-server-tracking-plan-status.md) |
| Direct SQL and aggregation at query time | Measure query cost, indices, and DB waits | [Performance plan](.scratch/signaldock/performance-plan.md) |
| SDK verification Dashboard | Prioritize receipt lists and details over analytics features | [Dashboard defaults](.scratch/signaldock/dashboard-verification-defaults.md) |

[Final design review](.scratch/signaldock/final-review.md) · [Glossary](GLOSSARY.md) · [Onepager HTML](docs/architecture/signaldock-onepager.html) · [README](README.md)
