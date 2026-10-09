# SignalDock performance measurement plan

Status: design approved; implementation and execution have not started. On 2026-10-08, the user selected option 1 in Q104 and delegated the remaining performance measurement decisions to the agent. The numbers below are project test hypotheses and acceptance targets. They are not official recommended specifications or achieved results. This delegation does not authorize changes to product contracts outside performance testing.

## Fixed conditions

- PostgreSQL: CPU limit equivalent to 2 CPUs, 2 GiB memory, Docker local named volume.
- Ktor: one instance, CPU limit equivalent to 2 CPUs, 1 GiB memory. Start with a test JVM heap limit of 512 MiB and observe native memory, GC, and OOM.
- A CPU quota does not allocate exclusive physical cores. Record host/VM CPU, memory, architecture, storage, Docker/JDK/DB versions, and image digests. Check applied settings with inspect and actual metrics during execution.
- Do not weaken PostgreSQL durability settings for performance. Keep fsync/synchronous_commit/full_page_writes enabled. Use the selected version's defaults for DB planner and memory settings, save the effective settings, and apply the same settings to every candidate.
- Use the local k6 CLI as the load generator. Do not upload to the cloud. Verify and pin its version during implementation. If it runs on the same host, record resource competition between the generator, VM, and app. A run with a saturated generator cannot establish server capacity.

## Data and queries

- Starting seeds of 10,000/100,000 events × uniform distribution over 30 days/70% in the last 24 hours × query windows of 1/7/30 days × 1/5 Dashboard clients = 24 combinations.
- Fix and record reference time T at midnight in Asia/Seoul. Query [T-N days,T). The actual collection API assigns receivedAt from server time; do not falsify it with past values. This test measures resource contention between historical queries and current writes. Verify real-time display correctness in separate end-to-end checks.
- Seed event types are product_view/cart_add/purchase at 70/20/10%, spread evenly across 100 synthetic installation IDs. Generate time and type distributions independently. These ratios do not estimate real customer behavior.
- Generate valid JSON events of about 1 KiB with a fixed seed. Follow numeric and string contracts. Measure DB/JSONB/index/WAL sizes separately. The seed tool is for the test DB only and does not change the normal API's receivedAt contract.
- Start each run from the same seed state in a separate test DB. Run ANALYZE after restoration, then warm up. Record rows added during warm-up. Do not reset the normal development/evaluation DB.
- The query performance fixture uses a 50-event first list page, no event-name filter, counts by type, and daily time buckets. These test values do not automatically set product page-size or bucket defaults. Verify API support during implementation and keep fixtures distinct from product defaults.
- Each Dashboard client queries the list and aggregates on a 5-second tick. If the same previous request is still running, skip overlap and record the skipped tick. All 5 clients start on the same tick. If list and aggregate APIs are separate, record the actual request count in the manifest. HTTP results do not measure browser rendering performance.

## Collection load

- 20 events/batch × 5 requests/s = target 100 events/s. Apply the same load during the 1-minute warm-up and 5-minute measurement.
- Use k6 constant-arrival-rate to schedule request starts independently of response completion. Do not lower the input target when responses slow down.
- Fix collection preallocated/max VU at 30/30. Use a 5-second whole-request timeout and no automatic retry. These test values provide headroom for the collection arrival rate and timeout; they are not server connection counts. Record dropped iterations from insufficient VUs as failures. Do not create catch-up bursts.
- Assign a new eventId to each event. Send the same contract version and valid payloads as the SDK, and validate ACK item counts, IDs, and statuses. Use pre-generated request fixtures to reduce generator cost during measurement.
- The measurement target is 1,500 requests/30,000 events, or 1,800 requests/36,000 events including warm-up. Record actual starts, completions, accepted, duplicate, rejected, and timeout counts separately.
- Baseline performance runs use unique events only. Keep ACK loss, retry, duplicate, conflict, and partial-rejection checks in the existing functional tests. Do not mix fault-injection results into performance statistics.

## Pool comparison and limits

- maximumPoolSize candidates: 2, 4, 8. Set each candidate's minimumIdle equal to maximumPoolSize to reduce variation from pool expansion. Check connection readiness during warm-up.
- Fix the DB concurrency limit at 8 for every candidate. Compare only pool size because changing both pool size and execution limits makes causes harder to separate. Distinguish admission control, such as a Semaphore, from the blocking IO execution area. A coroutine dispatcher alone does not guarantee a concurrency limit across an entire suspend operation.
- Treat connection acquisition through transaction completion and connection return as one DB operation. Each operation holds one connection. Do not put unrelated suspend/HTTP work inside a DB transaction.
- Test wait limits: admission 1 second, Hikari connectionTimeout 1 second, PostgreSQL statement_timeout 2 seconds. Apply DB limits at role/session scope, without forcing them on migration/codegen. The client timeout is 5 seconds. Record each measured interval and timeout type separately.
- A statement timeout is not a whole-transaction deadline. Client cancellation/timeout is not evidence of DB rollback. Avoid unlimited queue/admission waits and do not produce success ACKs on failure. Compare IDs after the run to determine whether a commit occurred after a timeout.
- These values form the benchmark profile. This document does not set the product-wide HTTP timeout or overload API contract. The previous spec values of a 5-second connection wait and 10-second statement timeout were not adopted; use this document's values for the benchmark.

## Duration, repetitions, and run order

- For each combination and pool candidate, run a 1-minute warm-up and 5-minute measurement independently 3 times. Allow up to 10 additional seconds to drain active requests, plus time to check DB state. Do not start the next run while DB work remains.
- 24 × 3 pools × 3 repetitions = 216 runs. The base intervals total 1,296 minutes = 21 hours 36 minutes. Data preparation, restoration, draining, pilot runs, and additional repetitions are separate. Distinguish human work time from automated execution time.
- Rotate pool order across repetitions: 2→4→8, 4→8→2, 8→2→4. Run measurements serially on one host. Do not run unnecessary competing loads, such as Android builds/emulators.
- Run the pilot with the large seed, recent concentration, 30-day query window, and 5 clients. If result trends and generator metrics are unstable, extend warm-up/measurement durations and apply the same new procedure to all candidates. The pilot does not count toward the 3 measurement repetitions.
- A restart alone does not prove that the OS cache is empty. Do not force-clear the host cache. Record DB buffer metrics before and after warm-up, but do not interpret shared buffer hits as proof of no physical disk I/O.

## Acceptance and selection

The criteria below are project targets set before measurement, not an external SLA. Do not lower them after measurement to hide a failure.

1. Correctness: require 0 incorrect ACKs for valid new events, unexpected duplicate/rejected results, unique-row mismatches, or aggregation errors. Use run IDs and generated ID lists to distinguish seed, warm-up, and measurement rows, then reconcile them after execution. Record rows stored after timeouts separately from successful responses.
2. Load validity: all 1,500 scheduled collection iterations in the measurement must start. Dropped iterations or generator saturation cannot produce a valid pass. If a slow server causes the generator to reach its limit, preserve the server symptoms and distinguish the causes.
3. Errors: require 0 HTTP errors, timeouts, or incorrect ACKs in baseline runs. Report Dashboard skipped ticks, and do not mark a run as passed if it cannot maintain normal polling.
4. Latency targets: collection ACK p95 ≤ 500 ms, list p95 ≤ 500 ms, and each aggregate API p95 ≤ 1 s. Measure from HTTP start to completion and state whether intervals such as client connection waits are included. Save p50/p95/max and actual sample counts.
5. Keep the results and distributions for each of the 3 runs. Do not label an average of percentiles as the overall p95. A query type may have only about 60 samples per run, so do not claim precise tail estimates or statistical significance. Extend observation and repeat under the same comparison conditions when results are near a threshold or vary greatly between runs.
6. Prefer the smallest pool that meets the criteria in every combination. If differences are unclear, explain the choice through stability and resource budgets without claiming higher speed. Report ties at the current fixed load as ties.
7. If no candidate passes, diagnose query plans/indexes, JVM, DB/generator resources, and wait intervals. Do not assume a larger pool will solve the problem. Repeat affected combinations after fixes and state the verified scope if final complete results are unavailable.

## Deliverables and execution boundaries

- Per-run manifest (JSON): Git revision, dirty diff hash, tool versions/settings/environment, fixture seed/hash, T, each resource limit, pool, run order, and start/end times.
- Results (JSON/CSV): per-request or histogram source data, per-run summaries, errors and ACK reconciliation, DB row/file sizes, Hikari active/idle/pending/acquire times, admission waits, DB query times, and CPU/memory/throttling/GC metrics. State which metrics were collected and which were not.
- Produce a Markdown result summary and comparison figures with standard plotting tools. Do not record credentials such as API keys.
- Preserve completed runs with manifests/hashes so execution can resume under the same conditions after an interruption. Preserve invalid runs with their reasons too.
- Tool installation, code implementation, DB creation, measurement, and scheduled execution have not started. Implement after the final understanding check for the full grilling process, then perform the main measurements after technical verification.

## Official sources checked

Checked on 2026-10-08. These documents support the mechanisms; they do not recommend this plan's numbers.

- [k6 open/closed models](https://grafana.com/docs/k6/latest/using-k6/scenarios/concepts/open-vs-closed/): use an arrival-rate model so collection injection rates do not fall as response latency rises.
- [HikariCP configuration](https://github.com/brettwooldridge/HikariCP#configuration-knobs-baby): roles of maximumPoolSize, minimumIdle, and connectionTimeout.
- [PostgreSQL client defaults](https://www.postgresql.org/docs/current/runtime-config-client.html): scope and behavior of statement_timeout.
- [PostgreSQL pg_prewarm](https://www.postgresql.org/docs/current/pgprewarm.html): distinction between OS/DB caches and possible eviction of warmed data.
