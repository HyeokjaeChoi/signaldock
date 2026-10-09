# SignalDock first-release spec

Status: Q127 full design agreement complete; implementation approved and ready to begin. Product code implementation has not started. Record build, device, and performance verification results separately.

Baseline date: 2026-10-09. Q127 approved entry into implementation. This baseline design is not evidence of completed build, device, or performance verification. This document is not an execution report. Keep personal conversations and résumé review records local only. The latest agreements are the items marked as agreed through Q126 in this document and the ADRs. Later agreements refine or replace earlier proposals. Read performance-plan.md for performance measurement and dashboard-verification-defaults.md for Dashboard defaults. Section 9 lists technical conditions to check during implementation. final-review.md summarizes the full review.

## 1. Goal and scope

This portfolio supports a Datarize Mobile SDK Engineer application. It adds evidence of SDK API design, delivery reliability, independent distribution, and compatibility management to existing app-development experience. Collect events in an Android customer app, store them on a local server, and inspect the results in a web Dashboard.

- Target date: a first complete version by 2026-10-22. The user's available work time is 10 to 15 hours per week, 20 to 30 hours total. This is not an estimate that guarantees completion.
- First release of an Android native SDK. The customer app explicitly calls `track()`.
- Shopping demo: product view → cart → simulated purchase. No real payment.
- One local project, collection-key checks, synthetic data, and a local Dashboard without login.
- JSON for both SDK and server. Generate Kotlin and TypeScript models from the OpenAPI contract.
- Deliver one entry point for server/DB/Dashboard startup, an SDK Maven folder-repository ZIP, an independent consumer app, reproduction instructions, and a short verification video.
- The first scope excludes iOS/Flutter SDKs, automatic events, user identify, campaign/push, funnel analysis, multiple projects/account management, public internet operation, and Maven Central publication.

Job reference: [Datarize Mobile SDK Engineer listing](https://groupby.kr/positions/12838). The personal résumé was reviewed locally only. Exclude its source and conversation records from repository uploads.

## 2. Agreed stack and boundaries

| Area | Choice |
| --- | --- |
| Android SDK | Kotlin, minSdk 24, Coroutines, Room/SQLite, Retrofit/OkHttp, kotlinx.serialization, WorkManager |
| Android demo | Jetpack Compose + ViewModel. The SDK does not depend on Compose/ViewModel |
| Server | Kotlin/Ktor, PostgreSQL, jOOQ, JDBC/HikariCP, Flyway SQL migrations |
| Dashboard | React/TypeScript, Vite, Recharts |
| Contract | OpenAPI source, per-component model generation, JSON transport |
| Distribution | Local Maven repository ZIP, AAR and dependency metadata, separate Gradle consumer app |

Handle JDBC connection acquisition through transaction completion in an IO execution area with limited concurrency. Ktor suspend APIs do not make JDBC non-blocking. Follow Q124 through Q125 for normal-operation wait/timeout policy and verify values through implementation and measurement. Benchmark settings are separate.

Apply Flyway migrations to temporary PostgreSQL → generate jOOQ code → compile the server. Apply the same migration set to the runtime DB. Add new migrations instead of modifying migrations already applied.

Separate collection requests and query responses by role, and reuse common schemas. Do not force Room entities, jOOQ records, or public SDK APIs to use transport DTO types. Do not edit generated code directly. The intended approach does not publish a separate shared Kotlin DTO artifact.

## 3. SDK public behavior

The public Kotlin package is local.signaldock from Q126. API names are design examples; fix exact signatures during implementation.

```kotlin
val result = sdk.track("product_viewed") {
    property("product_id", "P-001")
    property("quantity", 2)
    property("is_test", true)
}
val diagnostics = sdk.diagnostics()
```

### Collection

- `track()` is a suspend function. Run DB storage I/O off the main thread.
- Collection success means persistent storage on the device is complete. It is distinct from successful server receipt.
- Provide a small typed DSL for supported types. Do not expose transport DTOs or JSON-library types to customer apps.
- Customer apps define event names and property keys. Do not put shopping-specific models in the SDK.
- Properties are a flat object containing only strings, numbers, and booleans. Exclude nested objects, arrays, and explicit null; omit keys for absent values.
- Integers must be within ±(2^53−1); other decimals are finite Double values. NaN, Infinity, and out-of-range integers are validation errors. Apply the integer limit to integers passed through the Double path too. Send large IDs as strings and exact amounts as integers in the smallest currency unit within the permitted range.
- Report invalid input and expected storage failures through result types. Do not silently truncate content or convert values automatically.

### Lifetime and cancellation

- One SDK and one fixed configuration in the default process. Initialize explicitly in the customer app's Application.
- Reinitialization with the same configuration reuses the existing instance; a different configuration produces a configuration error.
- Retain only applicationContext. Manage the persistent queue and delivery independently of screen lifetime.
- On process recreation, recreate the SDK through the Application configuration path and recover the queue.
- Propagate caller coroutine cancellation. Do not convert CancellationException into a normal TrackResult failure.
- DB storage may already be complete at cancellation. Keep committed events and send them later. Automatically calling track again for the same action based only on cancellation can create a new ID and duplicate collection.
- Emit a developer WARN when cancellation leaves storage completion uncertain. Do not emit it when cancellation is known to precede storage. Logs are not guaranteed on forced process termination.
- Exclude event content, event names, installation IDs, credentials, and request bodies from logs. Do not automatically show toasts/dialogs to users.

### Common information

| Field | Meaning |
| --- | --- |
| eventId | SDK-generated UUIDv4, retained when resending the same event |
| installationId | Installation UUIDv4. [ADR-0001](../../docs/adr/0001-installation-scoped-identity.md) |
| occurredAt | Device time when track is handled. Does not guarantee the exact time of the business action |
| sdkVersion | SDK version at collection |
| receivedAt | Recorded on first server storage. Unchanged by resends |

Store collection information with the event. Sending after an SDK update must not change eventId, installation ID, time, or SDK version at collection. An API for customer-specified eventId/occurredAt is outside the first scope.

## 4. Local storage and delivery

| Item | Agreed initial value |
| --- | --- |
| Event size | At most 16 KiB of transport JSON, including common information |
| Batch size | At most 256 KiB for the whole body and 50 events |
| Foreground delivery trigger | 20 queued events or 10 seconds after storing the oldest pending event |
| Queue limit | At most 10,000 events or a total of 16 MiB of transport JSON |
| Queue overflow | Preserve existing events; return QueueFull for new track calls |
| Queue TTL | None in the first release |
| Permanent-rejection diagnostics | Most recent 100 entries |

Measure actual uncompressed UTF-8 JSON bytes, including escaping and the batch wrapper. The queue budget is not a hard cap on Room file size. Measure row/index/journal costs separately. Check limits and insert atomically.

Foreground and Worker use the same sender/Mutex to serialize batch selection → HTTP → ACK validation → queue updates. Store new track events separately from the delivery lock. Do not hold a DB transaction open while waiting for an HTTP response. Release the lock after each batch and check cancellation/execution conditions.

Retry lost responses, timeouts, and temporary failures with the same event IDs. Apply exponential backoff, jitter, and valid Retry-After. Foreground and Worker share persistent retry state. Do not discard events based only on failure counts. Batch triggers do not bypass retry waits. Do not use the actual delay cap to shorten a server-required Retry-After.

The SDK does not poll server DB state. Delete an event only after an unambiguous successful-receipt ACK. For permanent rejection, store diagnostics without event content and remove the queue entry in one local transaction. Unknown or ambiguous responses do not justify deletion.

Store installation ID, queue, failure diagnostics, and retry state in an SDK-specific backup-excluded area. Keep them across restarts and normal updates of the same installation. Data clearing, reinstallation, and supported device restoration do not inherit the old data, so unsent events can be lost. Do not change backup policies for other customer-app data. Arbitrary manufacturer-specific transfers are not guaranteed.

## 5. Server receipt and duplicate handling

Use an eventId unique constraint within the single project. Do not rely only on a prior lookup to prevent duplicate insertion.

1. Validate request structure, collection key, and size.
2. Validate each event's semantics in a parseable batch. Separate valid storage candidates from permanently invalid rejection candidates.
3. Insert valid items in a transaction. For ID conflicts, compare content with the confirmed existing row.
4. Return item-level receipt results after DB commit. Do not produce successful receipt on DB failure or rollback.

The same ID/content is duplicate success. The same ID with different content preserves the existing row and is permanently rejected as a conflict. Compare name, installationId, occurredAt, sdkVersion, and properties. Exclude receivedAt, batch composition, and request time. JSON key order has no meaning. Do not determine content equality from bytes/hashes alone.

Keep server events until explicit deletion or DB reset. There is no automatic TTL or separate dedup tombstone. Prevent duplicate storage/aggregation while the row remains. An old event resent after deletion may be stored again. Do not describe delivery as exactly-once.

The collection key is not a secret that cannot be extracted from the SDK. It checks access for local evaluation and does not replace user authentication or public-service security. Do not empty the queue by treating a key mismatch as permanent event rejection.

## 6. User screens and distribution

### Demo and diagnostics

Build the shopping flow and diagnostics screen with Compose/ViewModel. Call track once per user action. Do not automatically recollect on recomposition or result cancellation. Use screen text that distinguishes device collection results from server receipt results.

A read-only suspend diagnostics() snapshot provides pending count/payload size, last confirmed ACK time/delivery error/next eligible retry time, and recent permanently rejected IDs/codes/times. Query on screen entry and manual refresh. Do not add arbitrary queue deletion, event-content viewing, or a Flow subscription API. A recent ACK does not mean the whole queue has been sent.

### Dashboard

- Received-event list/details and counts by type/time. No funnel analysis.
- Sort lists, filter periods, and aggregate by receivedAt. Also show occurredAt in details.
- Poll every 5 seconds while visible, with manual refresh. Stop in hidden tabs and query on return. During an error wait, respect Q109's next eligible time.
- Do not overlap identical queries. On failure, show existing data, the error, and the last-success time.
- Do not automatically move details or past pages to the latest page.
- Do not infer SDK queue state from server receipt information. Do not label the difference between the two timestamps as pure network latency.

### Reproduction and deliverables

- Start the server, PostgreSQL, and built Dashboard with one Docker Compose entry command. Run the Android app separately.
- For development, support DB-only Compose with local Ktor/Vite execution.
- Preserve the DB volume on normal shutdown/restart. Provide a distinct reset command.
- Include the AAR and POM/required metadata in the Maven folder-repository ZIP. Install by artifact coordinates in a separate Gradle consumer app and verify R8 release build/execution. Do not substitute an SDK source project dependency.
- Document prerequisite tools, startup, emulator/physical-device connections, shutdown, reset, integration, limitations, and verification in README.
- Do not claim fully offline reproduction while ignoring first-run dependency downloads.

## 7. Completion criteria and verification plan

All items below are unexecuted plans. Distinguish the existence of configuration/documents/code from execution evidence.

| Verification | Completion criterion |
| --- | --- |
| Normal E2E | Demo track → persistent storage → server storage → Dashboard confirmation |
| Offline recovery | Retain 1,000 events and preserve stored IDs/content after process restart and reconnection |
| ACK loss | Blocking the response after server commit and resending do not increase unique server rows/aggregates |
| Partial rejection | Invalid events do not block valid-event storage; diagnostics and queue updates remain consistent |
| Content conflict | Reject different content with the same ID; preserve the original and first receivedAt |
| Concurrency | No queue loss during foreground/Worker contention, interruption, and resumption |
| Collection cancellation | Distinguish cancellation before/during storage and during result delivery after commit; verify required WARNs and retention |
| Capacity boundaries | Check 16 KiB/256 KiB/10,000 events/16 MiB boundaries, concurrent insertion, and space recovery after QueueFull |
| Distribution | Install the Maven ZIP in a separate consumer app, execute an R8 release, and connect to the server |
| Installation lifetime | Keep data on same-installation restarts/updates; verify exclusion on data clearing/reinstallation/supported restoration |
| Contract changes | Regenerate/compile models, compare prior schemas, and check prior-request/new-response fixture compatibility |
| DB changes | Check migrations, jOOQ generation, and server queries on empty and previous-schema DBs |

Representative loads use synthetic events of about 1 KiB including common information: normal 1 event/s × 60 seconds, offline accumulation of 1,000 events, and a target burst injection of 100 events/s × 10 seconds. Record actual injection rate and call concurrency. Do not report a result that misses the target injection rate as a success.

Record device, OS, build mode, and server environment with track completion p50/p95 latency, errors/duplicates/missing events, queue drain time, DB/sidecar sizes, and memory changes. No performance ceiling or throughput is guaranteed yet. Performance numbers do not replace functional correctness. Check core behavior on the minimum API and a selected latest stable Android version.

## 8. Additional implementation policies and individual agreements

The Q-specific agreements and later additions below define the current design. Fix implementation details, such as proposed directories and method signatures, within the existing contracts.

### A. Scheduling and failure recovery

Q57 agreed: The user selected the one-time Worker and 15-minute periodic recovery path below. Q72 added the Worker execution budget, Q73 the backoff base values, and Q74 Full Jitter.

- Use a unique one-time Worker requiring network connectivity. Check scheduling during initialization, queue additions, and foreground exit. Do not overwrite the customer app's whole WorkManager configuration.
- Recheck the queue and provide a unique periodic Worker every 15 minutes as a recovery path to prevent missed scheduling in races between active work and newly added events. Exit without HTTP when the queue is empty. Execution exactly every 15 minutes is not guaranteed.
- Q72 agreed: In one Worker execution, stop starting new batches after 10 batches or 30 seconds from execution start, whichever comes first. This is a soft budget checked between batches. Active-request timeouts and OS cancellation apply separately. Completion within exactly 30 seconds is not guaranteed. Hand remaining work to the next one-time schedule and retain the periodic recovery path. Preserve events without confirmed ACKs.
- Q73 agreed: The SDK temporary-failure retry base starts at 10 seconds for the first failure, doubles, and is capped at 5 minutes. This is the pre-jitter base, not a guaranteed minimum wait or execution time. A later server Retry-After takes priority. Fix overflow handling through implementation/tests.
- Q74 agreed: Apply Full Jitter, uniform from 0 to base B. Retries can occur almost immediately. Determine the next eligible attempt time once per failure, persist it, and share it between foreground/Worker. Do not redraw on process restart/Worker execution. Respect a longer Retry-After.
- Q75 agreed: Reset the temporary-failure count immediately after a valid whole ACK has been applied to the local queue. Even an ACK containing only known permanent rejections indicates communication/contract recovery. Apply this even when other queue items remain. The next new temporary failure starts with the 10-second base. A 2xx alone, restored connectivity, or app restart does not reset the count.
- The next eligible attempt time is shared by foreground/Worker. Do not sleep inside a Worker to wait. WorkManager scheduling delays may lengthen the actual wait, but must not advance the SDK's earliest retry time.

### B. Errors, ACKs, and data representation

Q58 agreed: Authentication/configuration errors use automatic rechecks every 30 minutes as described below. Persist state and the next attempt time so new events/process restarts cannot bypass the wait. Emit repeated-error WARNs mainly on state changes. Confirm the table's other unapproved defaults in later questions.

| Situation | Proposed default behavior |
| --- | --- |
| Network/timeout/408/429/temporary 5xx | Preserve + backoff. Classify clearly unsupported protocol errors as configuration errors |
| 401/403, clear configuration/contract errors such as an invalid endpoint/request format | Stop normal sending + diagnostics/WARN; preserve the queue. Recheck with at most one batch every 30 minutes and resume on success. Respect longer Retry-After and OS constraints. No HTTP when the queue is empty |
| Unparseable, missing, or contradictory ACK (Q59 agreed) | Apply none of the response. Preserve the whole batch, record request-level protocol diagnostics, and back off. Do not select partial results for deletion or confirmed permanent rejection |
| 413 for a multi-event batch (Q60 agreed) | Split into smaller batches. Keep the same eventIds. Do not retry before a valid Retry-After |
| 413 also rejects a single event (Q60 agreed) | Diagnose a size-contract mismatch; preserve the whole queue and stop normal sending. Recheck with that single request every 30 minutes, respecting longer Retry-After and OS constraints. Persist recovery state/next attempt time. Resume normal sending after a valid ACK is processed. Other events also wait; return QueueFull at the limit |
| Known per-event permanent rejection | Store diagnostics and remove the queue entry in one transaction |

- The response supplies each request item's index and status (accepted/duplicate/rejected) exactly once. Include eventId for valid IDs. The SDK compares the whole response against the immutable request-time list before applying it. Unknown statuses, duplicate indexes, ID mismatches, and omissions are not success.
- Q61 agreed: Repeated identical ID/content within a batch stores one DB row and returns a result for each request index. If the same ID has different content within the batch, reject the whole ID group as conflict, create no new row, and preserve any existing DB original. Process other IDs normally.
- Q69 agreed: The first release prevents duplicate storage through eventId uniqueness/content comparison, without a separate batch Idempotency-Key or response cache. A resend may return duplicate instead of the initial accepted result. Do not introduce Redis for this purpose.
- Q70 agreed: Aggregate stored event rows at query time with COUNT/GROUP BY, without separate aggregate tables, additive counters, or aggregate Workers. Write queries with jOOQ and verify actual query costs and required indexes.
- Q81 agreed: Send times as UTC RFC 3339 strings with millisecond precision. OpenAPI date-time alone does not enforce UTC/millisecond constraints; check actual generation/validation boundaries.
- Q82 agreed: Server input also accepts explicit valid numeric offsets and interprets/compares them as the same UTC instant. Standardize SDK output and server responses on UTC Z. Reject local times without offsets; do not infer the project timezone.
- Q83 agreed: Reject input times that cannot be represented losslessly in milliseconds. Allow equivalent forms such as .123000 and .123 without automatic truncation/rounding. Apply the same rule to event times and query boundaries. Align storage/API/cursor precision for server-generated receivedAt to milliseconds too.
- Q80 agreed: Period filters and aggregate buckets use start-inclusive/end-exclusive [start, end). Interpret date input at project-timezone calendar boundaries, then convert to UTC query instants. Include the whole final selected date; convert API end to the start of the following day.
- Q78 agreed: The Dashboard uses a shared project timezone independent of the browser. Use server configuration to align date filters, bucket calculation, and list/detail/chart display, and show the timezone. Provide startup configuration in the first release; do not add an administration screen or per-user override. Record the rationale in timezone-research.md.
- Q79 agreed: The initial timezone in local demo startup configuration is Asia/Seoul. Provide it as project configuration, not a hardcoded value.
- Q62 agreed: Compare validated numbers by value. 1, 1.0, and 1e0 are equal, as are -0 and 0. Distinguish string "1" from number 1. Do not use approximate equality: 1 and 1.0001 differ. Compare without precision loss and preserve stored event content on resends.
- Q63 agreed: Specifying the same DSL key twice, even with the same value, fails validation for the whole track call and stores nothing. Return expected input errors as TrackResult failures instead of exceptions.
- Q64 agreed: The SDK and server reject empty event names/property keys, whitespace-only values, and leading/trailing whitespace. Do not trim automatically. Internal whitespace/Korean text is not rejected by this rule alone. Preserve property string values. Fix the whitespace character set in the contract/fixtures.
- Q84 agreed: Do not apply Unicode normalization to names/keys; compare decoded code-point sequences exactly. Visually identical characters with different representations may be distinct names/keys. Align SDK/server/DB comparison and aggregation rules. Continue preserving property string values.
- Q85 agreed: Return validation failure for NUL characters or invalid lone surrogates before SDK storage. Independently validate on the server before DB storage too. Do not silently delete/replace names, keys, or string values. For a correctly parsed batch, use existing partial-acceptance rules for individual event errors.
- Q65 agreed: Specify the API contract version in a header, separate from the SDK release version. Increment the contract version for incompatible changes. Accept compatible optional-field additions within the same version by deploying the server first. Track changes with OpenAPI/Git release baselines. The first release implements only contract 1. Q66 defines existing queue contracts, Q67 unsupported versions/fields, and Q105 missing headers. Q106 sets the header name to SignalDock-Contract-Version.
- Q66 agreed: Store the transport contract version with queue items and send with the original contract after SDK updates. Batch by contract so the header and body match. Preserve original information such as eventId and sdkVersion at collection. A new SDK retains sending/ACK handling for supported older queues. The first release implements only contract 1; this does not promise indefinite support for prior contracts.
- Q67 agreed: Explicitly reject the whole request for unsupported contract versions and request-contract fields unknown to the server. The SDK preserves the queue and applies Q58's 30-minute recovery checks, respecting longer Retry-After, OS constraints, and persistent recovery state. Do not downgrade automatically. Free-form keys inside properties are exempt. Keep partial acceptance for per-event validation errors within a valid contract.
- Q68 agreed: The SDK ignores compatible additional response fields while retaining full existing ACK validation. Unknown status, missing fields, type errors, or contradictions require applying none of the ACK and preserving the queue under Q59. A change is compatible only if the existing ACK can still be handled correctly without reading the added fields.

### C. Small modules and local execution

Proposed directories: `sdk-android/`, `sample-android/`, `server/`, `dashboard/`, `contracts/`, `compatibility/consumer-android/`.

Q86 agreed: Start with one Gradle SDK publication module. Separate public API and storage/delivery/scheduling/diagnostics implementations into internal packages. Make implementation types internal where needed. Packages alone do not enforce dependency direction. Keep the demo and independent consumer app separate.

Q87 agreed: Do not add a DI framework to the SDK. Create objects at one initialization composition point and connect them through constructors. Manage SDK lifetime and process recreation; foreground/Worker use the existing shared instance. Inject fake clocks/transports at internal test boundaries. Do not require customer apps to use a DI framework or create internal objects.

Q88 agreed: Add interfaces/function dependencies only at necessary boundaries such as time, randomness, HTTP, and persistent queue. Do not wrap existing abstractions redundantly. Test pure validation and batch/backoff calculations directly. Preserve atomic storage operations. Fake tests do not replace actual Room/communication verification.

Q89 agreed: Manage the Android SDK/demo and Ktor server with separate Gradle builds/settings/wrappers. Share the OpenAPI source and connect generation/verification through root scripts. Keep Dashboard and distribution-verification consumer builds independent too. Actually verify version consistency, server builds without the Android SDK, and artifact installation in the consumer app.

Q90 superseded: Q91 replaces the policy of excluding OpenAPI generated files from Git and automatically generating them before compilation. Keep source schemas, pinned generator versions/settings/templates, and release baselines/fixtures in Git.

Q91 agreed: Include all generated OpenAPI Kotlin/TypeScript and jOOQ source in Git. CI regenerates into clean temporary output and checks file lists/content against committed results, failing on additions/deletions/modifications. Generate jOOQ from a temporary PostgreSQL schema with Flyway migrations, not from the runtime DB. Pin generation tools/settings/templates/environment and remove nondeterministic output. Separate normal compilation from generated-output update commands so automatic overwrites do not hide drift. Run the same checks and existing contract/integration tests on merge candidates. Follow generated-code-policy-review.md for details.

Q71 agreed: Store common fields in typed columns and dynamic properties in PostgreSQL JSONB. Align storable string/number ranges with SDK/server input contracts and separately validate the flat scalar rules. Q70 agreed on DB-query-based time aggregation.

Q76 agreed: Lists use a unique newest-first receivedAt/eventId order and cursor pagination. The first release does not support arbitrary page-number jumps. A cursor is not a snapshot or commit order, so one traversal cannot guarantee no missed concurrent inserts/late commits. Do not use it as a change stream that queries only rows after a timestamp.

Q77 agreed: Automatically requery only the latest first list page; provide manual refresh for past pages. Keep existing polling for visible aggregates and distinguish list/aggregate last-query times. 'Latest' requires explicit user action. If refreshing the current page changes results, invalidate subsequent cursor history. Return to the first page on filter changes. This does not retain a server snapshot.

The evaluation entry command handles migration/codegen/build/startup dependencies. If first-time setup fails, identify the stage. Do not reset the runtime DB arbitrarily for codegen. Under user delegation, Q92 finalizes the design to compare pools 2/4/8 with DB concurrency fixed at 8. Select the final pool after measurement. Q93 agreed: the measurement PostgreSQL container has a CPU limit equivalent to 2 CPUs and a 2 GiB memory limit. CPU quota does not allocate exclusive physical cores or guarantee processing performance. The previous 5-second connection wait and 10-second statement timeout were not adopted. Follow the separate wait/timeout settings in performance-plan.md for the benchmark profile. These are not performance guarantees; check actual timeout units and application boundaries.

Q126 agreed: First local Maven ZIP coordinates are `local.signaldock:sdk-android:0.1.0`, and the public Kotlin package is `local.signaldock`. This is a local-only namespace, not a claim of external namespace ownership or Maven Central publication. Fix exact execution commands in implementation deliverables.

## 9. Technical conditions to check on entry into implementation

Check these with actual tools, generated output, and builds instead of user-preference questions. If a check fails, discuss only necessary design changes without silently changing scope.

1. Verify and pin a compatible stable-release combination of Kotlin, AGP, Gradle, JDK, KSP, Room, WorkManager, Retrofit, Compose, server jOOQ, and related dependencies through official documents and builds. Select compile/target APIs separately from minSdk 24.
2. Check OpenAPI generator output for flat scalar properties, null/optional, and error responses with a small round-trip example. Compile SDK/server Kotlin models and Dashboard TypeScript models. Documentation alone does not prove that all codegen options work together.
3. A small adapter may handle scalar boundaries that generated types cannot express easily. Do not silently switch to an Any-based public API that ignores the schema or manually duplicate all transport DTOs.
4. Fix schema comparison tools such as oasdiff and the release-baseline storage approach. No earlier SDK has been published yet, so label the first compatibility test as a prior-schema fixture. Expand to an actual published-SDK matrix later.
5. Verify Room backup-excluded storage/sidecars, WorkManager initialization order/unique scheduling races, collection commit/cancellation, and R8 in a separate consumer app.
6. Fix expected SDK/server initialization failure results, logger-level settings, and timeout/retry details in implementation specifications that preserve the contracts above. Return to user choices if new features are needed.

## 10. Proposed implementation order

1. First check a compatible toolchain, OpenAPI round trips, and the independent consumer-app build path.
2. Implement server migrations, jOOQ, idempotent receipt, and query APIs; verify failure boundaries.
3. Implement SDK collection, persistent queue, cancellation, limits, and diagnostics.
4. Implement the shared sender, ACK application, retry, Worker, and backup boundaries.
5. Connect the Compose shopping demo and Dashboard.
6. Complete deliverables with the Maven ZIP, local startup entry point, physical-device or emulator verification, load records, documents, and video.

Individual design agreements are complete through Q126. Q127 reviewed the whole flow and approved entry into implementation. After agreement, split implementation issues into individual files in the local tracker. If initial verification reveals a schedule overrun, first discuss adjustable scope such as demo/UI refinement before weakening data-loss prevention conditions.

## Sources and decision records

- [Installation identity and backup ADR](../../docs/adr/0001-installation-scoped-identity.md)
- [OpenAPI and JSON ADR](../../docs/adr/0002-openapi-json-contract.md)
- [Installation ID research](installation-id-research.md), [Idempotency research](idempotency-research.md), [Idempotency design review](idempotency-design-review.md)
- [Protobuf comparison research](protobuf-contract-research.md), [Review revising the recommendation to unified OpenAPI](protobuf-design-review.md)
- [WorkManager scheduling and backoff](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work), [Coroutine cancellation](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/with-context.html)

Q94 agreed: start measurement with one Ktor server instance, a CPU limit equivalent to 2 CPUs, and a 1 GiB container memory limit. These are runtime measurement conditions, not Gradle build resource limits. Keep JVM heap distinct from total container memory, leave room for native memory and other costs, and verify details. This does not guarantee throughput or minimum runtime requirements.

Q95 agreed: store PostgreSQL data in a Docker local named volume. Keep preservation across normal shutdown/restarts and explicit reset. Record the actual storage/VM environment in results. Do not assume cache hits or specific I/O performance.

Q93/Q94 supplemental agreement: keep DB 2 CPU equivalents/2 GiB and one Ktor instance/2 CPU equivalents/1 GiB as initial measurement hypotheses. These values were not derived from an official formula or SignalDock measurements; do not label them recommended/minimum specifications. After setting load and acceptance criteria, compare resource and pool candidates, adjust, and record the basis.

Q96 agreed: compare mixed collection/query load with 10,000 and 100,000 accumulated synthetic server events. This is the project's measurement scope, not an estimate of real customer scale or a capacity guarantee. Keep it distinct from the SDK's offline 1,000-event scenario. Prepare seeds in a separate measurement DB and do not count them as SDK delivery successes. Specify data distribution, query windows, concurrency, cache conditions, and acceptance criteria later.

Q97 agreed: compare two receivedAt fixtures over the 30 days before fixed reference time T: uniform distribution and 70% concentrated in the last 24 hours. Each fixture uses Q96's 10,000/100,000 events in a separate measurement DB. These are synthetic distributions, not estimates of real usage. Fix seed, reference time, and generation rules; use identical starting data for each comparison. Query windows, concurrency, and cache conditions remain later decisions.

Q98 agreed: for each data size/time distribution, separately measure queries for the last 1/7/30 days with the same reference end T. Distinguish response latency for the period-filtered first list page, counts by type, and time aggregates. Fix T at a project-timezone date boundary and query [start,T). These are measurement query windows; they do not establish product defaults or maximum query periods. List page size, aggregate buckets, concurrency, and cache conditions remain later decisions.

Q99 agreed: measure 1 and 5 Dashboard query clients during event collection. Each client reproduces the list/aggregate queries of one visible Dashboard screen, 5-second polling, and overlap prevention. Do not equate client counts with concurrent HTTP requests or DB connections. Record actual request/completion rates and latency, and specify reproducible conditions such as synchronized starts. This is synthetic verification scope, not a supported-user-count guarantee.

Q100 agreed: measure the first server pool-comparison performance baseline after the same collection/query warm-up procedure. Record warm-up duration/repetitions and measurement intervals. Warm-up alone does not guarantee complete cache hits or JVM stabilization. Do not call a restart a cold-cache state with OS caches cleared. Keep existing restart-recovery functional verification.

Q101 agreed: measure each condition/pool candidate independently 3 times. Apply equivalent starting data and the same warm-up procedure each time. Alternate/rotate candidate order to reduce order bias. Preserve each run's latency distribution, request counts, errors, throughput, and variation. Do not label averaged p95 values as the overall p95. Three repetitions alone do not guarantee statistical significance or sufficient samples. Measure further if differences are unclear. Current data sizes 2 × distributions 2 × windows 3 × client counts 2 = 24 combinations, or 72 runs per pool candidate with 3 repetitions. Measurement length, pool-candidate count, and collection-load combinations add to the total cost.

Q102 agreed: start server pool comparisons with 1 minute of warm-up and 5 minutes of measurement per run. Duration alone does not establish warm-up completion or statistical sufficiency. If unstable or short of samples, adjust the procedure and repeat all comparison candidates under the same conditions. Running 72 trials serially takes 7 hours 12 minutes per candidate, excluding preparation/restoration. This choice does not extend SDK Q49's normal 60-second or burst 10-second scenarios. Set server mixed-load injection rates/generation separately.

Q103 agreed: generate pool-comparison collection load through a separate HTTP load client sending valid batches to the actual collection API. None of the options bypasses API validation, idempotency, or post-DB-commit ACKs. Do not claim direct HTTP load as SDK performance; retain separate SDK verification. Target event rate, batch size, concurrency, retries, and generator resources remain later decisions.

Q104 agreed: use 20 synthetic events of about 1 KiB per batch and 5 requests per second (target 100 events/s) as sustained server collection load. The initial choice targets 30,000 new events over the 5-minute measurement, or 36,000 including the 1-minute warm-up. Each run's 10,000/100,000 events are starting seed sizes that grow during collection. Use new eventIds; retry and duplicate-load policy is separate. Distinguish this from the SDK's 10-second burst and record actual request/receipt-completion rates. Request arrival model, concurrency, and overload handling remain later decisions.

## 11. Delegated performance measurement decisions

The user selected option 1 in Q104 and delegated the remaining performance measurement decisions. Use the [performance measurement plan](performance-plan.md) as the latest baseline for this area. It resolves previously deferred load generation, type distribution, measurement queries, warm-up, repetitions, pool comparisons, timeouts, acceptance, and deliverables. Resource values remain hypotheses; final pool size and actual performance are unmeasured. Keep normal product timeouts, query defaults, and API error contracts distinct from test settings.

Q105 agreed: a missing collection API contract-version header rejects the whole request with HTTP 400 and an explicit contract error, storing no events. The SDK preserves the queue under Q58 and checks recovery every 30 minutes. Do not automatically interpret it as contract 1. Q106 sets the header name to SignalDock-Contract-Version.

Q106 agreed: the contract-version header is `SignalDock-Contract-Version: 1`. Keep it separate from event metadata `sdkVersion` and do not add a new X- prefix.

Q107 agreed: Dashboard business-query APIs must also send `SignalDock-Contract-Version: 1`. Reject omissions with HTTP 400 and do not automatically interpret explicitly unsupported versions. Health checks/static files are exempt. This does not apply SDK queue retention/30-minute recovery policy to the Dashboard.

Q108 agreed: stop Dashboard automatic polling on a confirmed explicit contract-version error. Preserve the last successful results/time and show a stopped-update state with a manual page-reload button. Do not reload automatically. Do not classify normal network errors or arbitrary HTTP 400 responses as contract errors.

Q109 agreed: consecutive retryable temporary Dashboard query failures increase waits from 5→10→20→30 seconds, then stay at 30 seconds. On query success, reset that query's failure count and resume normal 5-second polling. Keep list/aggregate state separate. Respect longer valid Retry-After; tab return/manual data refresh cannot bypass waits or overlap prevention. Show the next eligible time and keep existing results/last-success time. Stop on explicit contract errors under Q108. Unlike the SDK, do not persist retry state.

The [remaining decisions and technical verification list](remaining-decisions.md) is the current task list reflecting agreements through Q126. Do not treat area counts as question counts or ask the user to reselect already agreed/delegated items.

## 12. Dashboard role confirmation and default delegation

The user reconfirmed that SDK development is the focus and the Dashboard verifies results. Record evidence and concrete values in [vendor usage flows and defaults](dashboard-verification-defaults.md), and stop Dashboard UX selection questions. Focus on today's received list, a 50-event cursor page, exact name/ID filters, and individual stored-event details. Keep existing type/time charts as supporting information. Queries allow up to 30 calendar dates with past-range navigation, pages up to 100, and hour/day aggregates up to 720 buckets. Do not change retention TTL or performance measurement scope.

Q110 agreed: return expected initial configuration errors and storage-preparation failures through explicit results such as InitResult.Failure. Server connectivity or server approval of the key is not required for initialization success; allow offline initialization. Do not discard an existing successful instance because of invalid reinitialization. Do not swallow cancellation/fatal errors as expected failures. Under Q111, call track after suspend initialization returns a success result and SDK instance.

Q111 agreed: after suspend initialization returns InitResult.Success(sdk), the host calls track on that instance. Do not provide a usable SDK instance before success. Prepare storage off the main thread; coroutine waiting must not block the UI thread. The SDK does not guarantee retention of events that occur in the host before readiness and are never passed to the SDK. Do not introduce a preparation-time handle or separate in-memory event buffer.

Q112 agreed: after storage initialization fails with the same valid configuration, the host may explicitly call initialize again to retry. The SDK does not repeat automatically. Clean up failed-attempt resources without deleting/regenerating the existing queue or installation ID. Repeated calls with the same configuration after success reuse the existing instance under Q37.

Q113 agreed: concurrent initialize calls with the same configuration share one active initialization attempt and result. Return the same SDK instance on success and share that attempt's failure on failure. Waiting calls do not automatically start a new attempt after failure. Retry through a new explicit call under Q112. Concurrent calls with different configurations return configuration errors without changing existing work.

Q114 agreed: shared initialization continues in an SDK-managed Application/process-lifetime scope. Each initialize caller cancels only its own wait. Started work continues even if all waiters cancel. Reuse successful instances on later same-configuration calls; do not automatically retry initialization failure. Propagate CancellationException to cancelled callers. Completion before process termination is not guaranteed; this does not mean using GlobalScope.

Q115 agreed: default SDK Logcat output is WARN/ERROR. If the host explicitly enables DEBUG, add diagnostic state such as initialization, delivery, and retry scheduling. Keep existing WARNs for uncertain cancellation/configuration errors. At every level, exclude event content, event names, installation IDs, credentials, and HTTP bodies. Do not print arbitrary exception content unchanged. diagnostics() and API results are independent of log settings.

Q116 agreed: Logcat is the only public log destination in the first release. The host sets log level and queries summary state through diagnostics(). Do not add a public custom LogSink. Keep internal test logger substitution distinct from a public extension API.

Q117 agreed: set log level during initialization and fix it for the SDK lifetime. Do not provide a runtime setter. The host can change initialization configuration for the next process start. Keep DEBUG's sensitive-data exclusions.

Q118 agreed: invalid initial configuration returns a failure result without creating new SDK state. Later initialize calls validate their new input again. Do not provide a separate configuration-edit API. Continue rejecting configuration changes while initialization is active or after an instance succeeds. Do not alter the existing instance or persistent data. Same-configuration retries after storage-preparation failure follow Q112.

Q119 agreed: the initial whole-call callTimeout for SDK batch HTTP requests is 30 seconds. This is distinct from the server performance-test client's 5 seconds. A timeout does not mean the server stored nothing; retry unacknowledged events with the same IDs. It is separate from the Worker's 30-second soft budget. Phase-specific timeouts and host configuration scope remain later decisions.

Q120 agreed: initial SDK connectTimeout/readTimeout/writeTimeout values are 10 seconds each, applied with the 30-second whole-call callTimeout. Read/write limits apply to individual I/O waits, distinct from total response duration. Preserve unacknowledged events on every timeout.

Q121 agreed: the host may override whole-call/connect/read/write timeouts at initialization. Defaults are 30/10/10/10 seconds, fixed for the SDK lifetime. Validate positive finite values and the HTTP implementation's representable range; reject 0/unlimited. Return invalid values as configuration-validation failures. This choice does not include raw OkHttpClient injection.

Q122 agreed: keep OkHttp retryOnConnectionFailure=true to allow connection recovery within the same Call. Update persistent retry state after the SDK sender classifies the final transport result. Internal connection-failure callbacks alone do not increase SDK backoff. Internal resends retain the same eventId. Verify status-code follow-ups separately in the adopted version and preserve existing SDK retry contracts.

Q122 optional implementation-observation proposal (adoption not required): create an in-process diagnostic attemptId for each SDK delivery attempt and connect it to OkHttp Call's EventListener.Factory and, if needed, a request tag. Attempt ID is not a server Idempotency-Key or persistent eventId. The sender directly records retry reason, failure count, and nextRetryAt for SDK retry scheduling. EventListener observes callStart/end/failed and connect/request/response phases. Connection-failure/reconnection counts differ from HTTP resend or server receipt counts; repeated callbacks alone do not establish retry causes. Successful Call completion is distinct from valid ACK and successful local application. The listener only observes; it does not modify the queue, schedule retries, or perform blocking I/O. Test actual callback order with the selected OkHttp version.


### Q122 vendor research supplement: 2026-10-09

The pinned-source research in [transport-retry-vendor-research.md](transport-retry-vendor-research.md) found SDK queues and retry policies in all three vendors, but did not find attemptId + EventListener in the examined default sender/transport paths. Default transports and retry-state persistence also differ. Detailed instrumentation is therefore an optional proposal, not a common industry requirement. The recommendation was to start with minimal logs (delivery start, final result, duration, next retry time) and defer detailed instrumentation. The user adopted this minimal-diagnostics approach. Defer attemptId and detailed EventListener instrumentation in the first implementation. Keep existing log levels and sensitive-data exclusions.

OkHttp can perform same-Call follow-ups for some 408 responses, 503 with Retry-After: 0, and certain HTTP/2 421 responses. retryOnConnectionFailure=false does not block every follow-up. Keep the existing Q122 agreement permitting connection recovery. The user adopted the boundary that permits OkHttp's limited internal retries/follow-ups within one Call and applies SDK persistent backoff when the final result is retryable. This does not guarantee an SDK cooldown after every intermediate HTTP response. Keep Retry-After/queue retention policy for the final result received by the SDK. Q123 prohibits automatic HTTP redirects. Implementation/runtime verification has not been performed.

Q122 final confirmation: events are already queued before HTTP calls. Before a valid ACK is applied, do not delete them based only on timeout or delivery failure. For a retryable final result, create a new Call after backoff with the same eventId. Authentication/configuration errors follow existing per-error hold policies. Apply Q115's log-level/sensitive-data rules to minimal logs too.


Q123 agreed: prohibit automatic HTTP redirects for SDK collection requests, including same-origin redirects. Redirect responses are not valid ACKs, so preserve the queue and apply the existing configuration-error hold policy (recovery checks every 30 minutes). WARNs follow existing state-change/sensitive-data rules. Do not automatically change the next request address from Location. Keep OkHttp connection recovery and limited status follow-ups permitted. Verify redirect-disabling settings in the adopted version.


Q124 agreed: when the server has no processing slot before DB work starts, wait for a bounded time. If no slot becomes available in time, return HTTP 503 and positive Retry-After. Do not allow unlimited waits. Set exact wait time/concurrency through implementation and measurement; do not automatically copy benchmark settings into normal operation. The SDK preserves the queue and applies existing backoff/Retry-After policy. Execution timeout/cancellation/commit boundaries for started DB work follow Q125.


Q125 agreed: design processing-slot/connection acquisition waits, SQL execution, commit/response, and error-cleanup time within one request time budget. Avoid independently increasing timeouts so waits accumulate. Use PostgreSQL statement_timeout as the default SQL execution limit and set lock_timeout shorter. A statement limit is not a whole-transaction limit; verify multiple-SQL and commit/rollback boundaries separately. Tune for normal lock contention from concurrent duplicate requests with the same eventId. 500ms and 2 seconds are explanatory examples, not adopted values. Do not automatically copy benchmark settings into normal operation either.

Apply limits with SET LOCAL or equivalent inside the transaction on the same connection, preventing settings from leaking on pool reuse. After DB errors, roll back or discard unusable connections. Do not assume that ending the HTTP wait stops DB work. Acknowledge uncertain storage outcomes when the commit response is lost; recover through same-eventId resends and server duplicate handling. Existing SDK backoff handles retries for temporary DB failures. Do not add a separate automatic server retry loop. Record processing-slot waits, connection waits, lock timeouts, and statement timeouts separately without sensitive information.

Implementation verification: reproduce lock contention, slow SQL, pool exhaustion, cancellation/rollback completion and connection reuse, and response loss after commit. A timeout response alone does not establish no storage or resource reclamation. Keep the existing post-commit collection ACK rule. Exact normal-operation values and driver/server cancellation integration remain implementation checks. This agreement does not mean execution verification is complete.
