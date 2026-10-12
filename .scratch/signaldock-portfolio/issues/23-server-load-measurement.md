# 23. Server load measurement (part of issue 08)

Status: ready-for-agent
Type: task
Blocked by: None
Execution: In progress (harness and pilot done; matrix pending user decision)

## Scope and completion criteria

The server part of `.scratch/signaldock-portfolio/performance-plan.md`: fixed resource limits, seeds (10,000 and 100,000 events), collection load with k6 constant-arrival-rate (100 events/s), concurrent Dashboard queries, HikariCP pool comparison (2, 4, 8), duration, repetitions and run order, acceptance and selection, and the deliverables (per-run manifest, raw results, Markdown summary, figures). Local k6 only; no cloud upload.

Done when the harness runs the plan, the pilot is recorded, the measured runs and their verdicts are recorded with raw data, and invalid runs are kept with their reasons. A run that misses the target injection rate is not reported as a pass.

## Comments

2026-10-12: Split from issue 08.

2026-10-12: Harness and pilot done (branch `impl/23-server-load-measurement`). The matrix has not started.

- Harness map (`perf/`): `compose.perf.yml` (override, project `signaldock-perf`, DB 2 CPUs / 2 GiB, Ktor 2 CPUs / 1 GiB, benchmark profile), `seed.py` (fixed-seed seeds, template DBs), `load.js` (k6: collection + Dashboard polling), `run_one.py` (one run: restore, ANALYZE, warm-up, measurement, drain, checks, manifest, raw results, verdict), `matrix.py` (lists or resumes the 216 runs; default runs nothing), `summarize.py` (Markdown, per-minute trend, figures). Decisions: ADR-0013. Design sections: `docs/design-index.md` section `perf/`, performance-plan.md, architecture.md "DB time budgets and queries", ADR-0004.
- Product change: env settings `SIGNALDOCK_DB_POOL_SIZE`, `_POOL_MIN_IDLE`, `_CONCURRENCY`, `_ADMISSION_MS`, `_CONNECTION_MS`, `_LOCK_MS`, `_STATEMENT_MS`, and `SIGNALDOCK_POOL_STATS_MS` (off by default). Defaults equal the previous behavior (`DatabaseLimitsEnvTest`).
- Pilot commands: `python3 perf/seed.py build --size 100000 --dist recent70`, then `python3 perf/run_one.py --seed-size 100000 --dist recent70 --window 30 --clients 5 --pool 4 --warmup 60 --measure 300 --run-id pilot-1 --position pilot --pilot`, then `python3 perf/summarize.py perf/results/pilot-1 --out perf/results/pilot-1/report.md`. Pool 4 is my choice; the plan does not name a pool for the pilot. Result in `perf/results/pilot-1/` (does not count as a measured run). Verdict by the plan's criteria: pass. All 1,500 measurement iterations started, dropped_iterations 0, 0 non-ok responses, 0 skipped Dashboard ticks. p95 ms: collect 27.2, list 146.9, by-name 166.7, by-time 169.0 (300 samples each for the three query types, 1,500 for collect). Per-minute p95 stayed within about 1.4x for every endpoint. Generator k6 CPU avg 1.6 %, max 7.1 %. 36,020 events accepted, all stored, no duplicates; API aggregates equal SQL. Trends are stable, so no extended durations are proposed. This is one run on one pool: it shows the harness works and the host has headroom, not a pool choice.
- Timing on this host (Apple M2 Pro, OrbStack, 12 CPUs): one run with 60 s warm-up and 300 s measurement took 368 s wall clock including restore (1 s), server start (about 2 s), drain (0.1 s), and checks. 216 runs: about 22.1 h. Reduced options: 1 repetition (72 runs) 7.4 h; 2 repetitions (144) 14.7 h; large seeds only, 3 repetitions (108) 11.0 h. With doubled durations (120 s + 600 s), one run is about 12.3 min and 216 runs about 44 h. Seed build is a one-time cost of about 1 min per seed after the first image build (about 45 s with cached layers).
- Limits: matplotlib is not installed, so no figures; `figure-data-latency.csv` holds the points. No `pg_stat_statements`, so no per-statement DB time. `statement_timeout` is applied per query with `SET LOCAL`, not at role scope. The existing driver property `socketTimeout=2` s equals the 2 s statement limit; it may close the connection before PostgreSQL reports the timeout when a query reaches 2 s (check in any run that shows timeouts). The perf Compose project, its volume, and image were removed after the pilot, so seeds must be rebuilt before the matrix. Raw k6 output and server log are not committed (gitignored).
