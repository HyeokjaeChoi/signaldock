# SignalDock architecture onepager content

Baseline: 2026-10-09, Q127. Design is complete and implementation is approved. Product code has not started. Functional checks are pending; performance has not been measured.

## Summary

SignalDock is a planned Android event collection SDK. It stores events on the device, retries with the same eventId, and transfers storage responsibility only after an ACK sent after server commit.

## Architecture overview and introduction

### Architecture overview

- Android: Compose demo → public SDK API → durable Room queue → shared sender.
- WorkManager and foreground triggers use the same sender and Mutex.
- Local server: Retrofit/OkHttp JSON requests → Ktor → jOOQ/JDBC/HikariCP → PostgreSQL.
- Dashboard: React/TypeScript/Vite/Recharts. It queries Ktor to inspect events stored on the server.
- Keep the overview to eight nodes. Explain contract generation and distribution checks in a separate supporting strip.

### 60-second introduction

SignalDock is my Android SDK portfolio for the Datarize Mobile SDK Engineer role. I designed it to extend my app development experience into public APIs, durable queues, failure recovery, contract management, and artifact distribution. A successful track call means Room has committed the event. Foreground triggers and WorkManager share one sender. The server checks eventId and content for duplicates, then sends an ACK after database commit. If the ACK is lost, the SDK resends the same ID. It updates the queue only after validating the full ACK. Planned checks cover OpenAPI generated-source drift and Maven ZIP installation in an independent R8 consumer app.

## Contract generation and distribution

1. Generate Kotlin models for the SDK and Ktor, and TypeScript models for the Dashboard, from OpenAPI. Keep the public SDK API, Room entities, jOOQ records, and transport DTOs separate by role.
2. Apply Flyway SQL migrations to a temporary PostgreSQL schema, generate jOOQ sources, then compile the server. Do not reset the runtime database for code generation.
3. Track generated Kotlin, TypeScript, and jOOQ sources in Git. Regenerate into an empty temporary output directory and compare file lists and contents. Compilation does not overwrite generated files. Keep schema diffs, fixtures, and integration checks.
4. Package an AAR, POM, and metadata in a Maven repository ZIP. Install `local.signaldock:sdk-android:0.1.0` in an independent Gradle consumer app. Verify artifact installation and an R8 release run without an SDK source project dependency.

## Event flow and storage responsibility

The sequence has five actors: host app, SDK runtime (collection and sender), Room, Ktor, and PostgreSQL. Track storage does not use the sender Mutex.

1. The host app calls track after initialization succeeds.
2. The SDK validates input and stores the event atomically in Room.
3. After Room commits, the SDK returns collection success. A server connection is not required.
4. The sender checks the trigger and retry eligibility time, then selects a batch.
5. Keep Room transactions closed during HTTP. Serialize foreground and Worker delivery with one Mutex. New track writes run independently of that lock.
6. Ktor validates the contract, key, size, and events, then stores them in PostgreSQL.
7. Return per-item ACKs after the server database commits.
8. The SDK validates IDs, counts, statuses, and consistency across the full ACK. An unknown or ambiguous ACK preserves the entire batch.
9. Apply a valid ACK in a local transaction. Remove successfully received events. For permanent rejections, atomically save recent diagnostics without event content and remove the events.

The collection commit in Room and the server commit are the two storage responsibility milestones. The final Room commit confirms local ACK application. Reset the retry failure count only after this third commit succeeds.

## Failure and retry

| Condition | Planned behavior |
| --- | --- |
| ACK lost after server commit | Retry with the same eventId. Matching content returns duplicate success. Keep the original receivedAt. |
| Same ID, different content | Permanently reject as conflict. Preserve the original server record. |
| Unreadable, missing, or inconsistent ACK | Apply no partial results. Keep the full batch, record a protocol diagnostic, and use backoff. |
| Final transient failure | Choose and persist the next retry time once. Foreground triggers and the Worker share it. |
| Authentication, configuration, or contract error; redirect | Keep the queue and pause normal delivery. Probe recovery with at most one batch every 30 minutes. Honor a longer Retry-After and OS constraints. |
| Within a single Call | Allow OkHttp connection recovery and limited status follow-ups. An SDK cooldown is not guaranteed between intermediate responses. |

SDK backoff base B starts at 10 seconds, doubles after each failure, and caps at 5 minutes. Full Jitter uses U(0,B), so a retry can be almost immediate. A valid Retry-After takes precedence if it requires a later retry. Process restarts and new triggers cannot bypass the wait. Recovery inside one Call and SDK retry with a new Call after the final result are separate layers. Follow no redirects, including same-origin redirects.

The server applies its eventId unique constraint and content comparison per event. IDs remain unchanged when batches split or regroup. Delivery is not exactly once. An old event can be stored again after its server row is deleted.

## Lifecycle and resource boundaries

- Initialization uses one SDK instance and one configuration in the default process. Calls with the same configuration share initialization and reuse the successful instance. Reject different configurations. After failure, the host must explicitly call initialization again. Cancelling one caller's wait does not cancel shared initialization.
- Track propagates CancellationException. An event may already be committed when the caller is cancelled. Calling track again can collect the same action twice with different eventIds.
- The installationId is a UUIDv4 stored in SDK-specific noBackupFilesDir. Exclude the queue, diagnostics, retry state, and database sidecars from backup too. Keep the host app's other backup policies. Clearing app data, reinstalling, or using supported restore paths can lose unsent events. Not all manufacturer transfer tools are covered.
- Bound admission and connection waits. Failure to obtain an admission slot returns 503 with a positive Retry-After. Run blocking JDBC in an IO execution area with bounded concurrency. A suspend function does not make JDBC non-blocking.
- Use lock_timeout < statement_timeout. A statement timeout is not a deadline for the full transaction. A timeout does not prove that storage failed, rollback completed, or a connection was released. Verify commit, rollback, cancellation, and pool reuse separately. The server has no automatic retry loop.
- App diagnostics show a snapshot of the device queue, recent ACKs, and next retry. The Dashboard shows stored server results. Neither establishes the other side's state. The difference between occurredAt and receivedAt is not network latency alone.

## Initial operating values

These are agreed design values, not performance results.

- Event JSON: up to 16 KiB. Full batch: up to 256 KiB and 50 events.
- Queue: up to 10,000 events or 16 MiB of total wire JSON. At capacity, keep existing events and return QueueFull for new track calls. This is not a Room file size cap.
- Foreground trigger: 20 events or 10 seconds after storing the oldest pending event. Respect retry waits.
- WorkManager: unique one-time work plus 15-minute periodic recovery. Exact scheduling is not guaranteed. Start no new batch after the Worker's 10-batch / 30-second soft budget.
- Default HTTP timeouts: 30 seconds for the full Call; 10 seconds each for connect, read, and write. Validate and fix them at initialization. Read/write limits apply to individual I/O waits.
- Dashboard: poll every 5 seconds while visible. Show today's receipts, 50-item cursor pages, exact name/ID filters, and queries spanning up to 30 calendar dates. Keep detail and historical pages in place.
- No queue TTL or automatic server TTL. Keep the 100 most recent permanent-rejection diagnostics.

## Interview questions

### Why focus on an SDK?

I want to extend my app development experience into public APIs, lifecycle management, offline recovery, artifact distribution, and compatibility. The Dashboard helps verify server receipt.

### Why Room and WorkManager?

Room supports commits and atomic queue updates. WorkManager provides a delivery retry path after process termination. It does not guarantee exact scheduling or execution after force-stop.

### Why OpenAPI and JSON over Protobuf?

There is no current requirement for binary transport. OpenAPI supports contract generation and change comparison. Collection and queries share one codec.

### Why use event-level idempotency?

Retries and HTTP 413 splitting can change batch membership. Each event keeps its eventId. A unique constraint prevents concurrent duplicate inserts; content comparison distinguishes duplicate from conflict.

### Why jOOQ instead of an ORM?

The server needs explicit handling of unique collisions, content comparison, JSONB, aggregates, cursor queries, and transaction boundaries. Generate types from the Flyway schema. There is no measured speed comparison with an ORM.

### Why not claim exactly-once delivery?

The server may commit even if the HTTP response is lost. Retry with the same ID and server deduplication provide recovery. Deduplication does not extend beyond deletion of the server row.

### How will the design be verified?

Planned runs cover 1,000 offline events and process restart, ACK loss after commit, conflicts, partial rejections, ambiguous ACKs, concurrent senders, cancellation, capacity, backup exclusions, contract drift, migrations, and an independent R8 consumer app. Record execution evidence before reporting results.

## Deliverables and verification status

- Planned deliverables: Android SDK, Compose shopping demo, one Docker Compose entry point for Ktor/PostgreSQL/Dashboard, Maven ZIP, independent consumer app, README, raw verification data, a summary, and a short video.
- Outside the first release: iOS/Flutter SDKs, automatic events, identify, campaigns/push, funnels, multiple projects/login, public hosting, and Maven Central.
- Functional evidence is pending. Record the environment, Git revision or alternative source hash, and ID reconciliation for each run.
- Performance is not measured. Separate Android track p50/p95, queue drain, file size, and memory from server HTTP latency. Direct HTTP load does not measure SDK performance.
- Planned server baseline: 24 conditions × pool sizes 2/4/8 × 3 repeats = 216 runs. Each run has 1 minute of warmup and 5 minutes of measurement: at least 21 hours 36 minutes of automated runtime before setup and recovery. 100 events/s is the target input rate; ACK p95 ≤ 500 ms is the acceptance target. Neither is a measured result or an SLA.
- Open checks: compatible toolchain versions, generated scalar types, exact API signatures, normal-runtime database timeouts, WorkManager contention, JDBC cancellation and commit, device behavior, and R8 execution.

## Local sources

Keep these source documents at their relative paths when moving the HTML.

- ../../.scratch/signaldock-portfolio/spec.md
- ../../.scratch/signaldock-portfolio/final-review.md
- ../../.scratch/signaldock-portfolio/performance-plan.md
- ../../.scratch/signaldock-portfolio/dashboard-verification-defaults.md
- ../adr/0001-installation-scoped-identity.md
- ../adr/0002-openapi-json-contract.md
- ../../GLOSSARY.md

## Output format

Use the approved white background, navy text, and teal accents. Deliver one scrolling HTML page with inline SVG, embedded CSS, system fonts, internal anchors, and print support. The page makes no external requests.
