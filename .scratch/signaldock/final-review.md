# Full review of the first release: Q127 approved

2026-10-09. This summarizes agreements through Q126. It is not a new feature proposal or a report of completed implementation. spec.md, ADRs, and delegated documents define the detailed contracts.

## Purpose and evaluation flow

For the Datarize Mobile SDK Engineer application, demonstrate Android SDK public API design, persistent storage, failure recovery, contract management, and independent distribution. The Dashboard helps verify results received by the server.

1. The evaluator starts the local server, DB, and Dashboard. After SDK initialization succeeds, the demo app collects product-view, cart, and simulated-purchase events.
2. Successful track means persistent storage in Room. Collection works offline, and explicit results report queue limits or invalid input.
3. The shared sender sends batches. Distinguish OkHttp recovery/follow-up within one Call from SDK persistent backoff after the final result. Do not follow redirects.
4. The server handles duplicates with eventId uniqueness and content comparison, then ACKs after commit. The SDK validates the whole ACK and applies it to the queue. Resending the same ID recovers from ACK loss.
5. Read-only app diagnostics show the queue, recent ACK, and next retry. The Dashboard shows stored server events, details, and supporting aggregates. Do not infer one side's state from the other screen.
6. Install local.signaldock:sdk-android:0.1.0 from the Maven ZIP in an independent consumer app and verify R8 release execution. The public Kotlin package is local.signaldock.

## Implementation baseline

- Android: Kotlin/minSdk24/Room/Coroutines/Retrofit·OkHttp/WorkManager. One published SDK module, manual DI. Use Compose only in the demo.
- Server: Ktor/PostgreSQL/jOOQ/HikariCP/Flyway. Limit wait and execution times, roll back, and clean up connections. The SDK handles retries; the server has no automatic retry loop.
- Contract: unified OpenAPI and JSON. Track generated Kotlin/TypeScript/jOOQ source in Git and check regeneration differences. Keep SDK release and contract versions separate.
- Dashboard: React/TypeScript/Vite/Recharts. Keep the delegated defaults focused on verifying SDK receipt.
- Local delivery: Compose entry command, Maven ZIP, independent consumer app, README, verification results, and a short video. Public operation, Maven Central, iOS/Flutter, automatic collection, and identify are excluded.

## Required evidence

Verify offline retention of 1,000 events and recovery after process restart; ACK loss after commit and duplicate prevention; partial rejection/content conflicts/ambiguous ACKs; concurrent senders/cancellation/queue limits; initialization races; installation lifetime/backup exclusion; DB locks/execution timeouts/connection cleanup; contract and migration changes; and an independent R8 consumer app. Follow spec.md for detailed completion criteria.

Keep the existing performance-plan.md scope. The server baseline is 24 conditions × 3 pool candidates × 3 repetitions = 216 runs. Each run has a 1-minute warm-up and 5-minute measurement, for at least 21 hours 36 minutes excluding preparation and restoration. This is automated execution time, separate from the user's available work time. Do not guarantee completion or measured values in advance.

## Conditions to resolve during implementation

Verify compatible dependency versions, codegen scalar representation, exact API signatures, normal-operation timeout values, WorkManager races, and JDBC cancellation/commit behavior during implementation. Discuss any required policy changes again. The 500ms and 2-second examples were not adopted.

## Next step

The user selected option 1 in Q127 and approved the full understanding check and entry into implementation. Create individual implementation issues under issues/ and start with toolchain and contract checks. Starting implementation does not mean functional or performance verification is complete.
