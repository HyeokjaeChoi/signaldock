# Server load measurement summary

Source: `perf/summarize.py`. Values come from `summary.json` and `requests.csv` of each run. Targets are project targets set before measurement, not an SLA. Latency is HTTP start to completion as seen by k6 (sending + waiting + receiving); client connection wait is not included and is reported as `blocked+connecting max`.

## overload-1 (overload run, Q133)

- Verdict: **pass**
- Git: `0c80660547fa`, dirty=False, diff sha256 `e3b0c44298fc1c14`
- Server limits for this run: concurrency 2, admission wait 100 ms, pool 4; seed 100000 rows, recent70, window 30 d
- Phases: baseline 30 s, overload 30 s, recovery 60 s. Collection 5 rps (overload phase: 200 rps); probe 1 rps (list); heavy 150 rps by-name in the overload phase only. Dropped iterations: 0

| Phase | Endpoint | requests | status counts | Retry-After values | error codes | p50 ms | p95 ms | p95 of 200 ms | max ms |
|---|---|---|---|---|---|---|---|---|---|
| baseline | collect | 149 | {'200': 149} | - | - | 21.6 | 45.1 | 45.1 | 129.3 |
| baseline | probe | 30 | {'200': 30} | - | - | 11.8 | 33.7 | 33.7 | 398.7 |
| overload | collect | 5805 | {'200': 2102, '503': 3703} | {'1': 3703} | {'TEMPORARILY_UNAVAILABLE': 3703} | 102.0 | 107.7 | 107.8 | 176.2 |
| overload | probe | 30 | {'200': 6, '503': 24} | {'1': 24} | {'TEMPORARILY_UNAVAILABLE': 24} | 101.1 | 120.6 | 120.6 | 170.3 |
| overload | heavy | 4490 | {'200': 2047, '503': 2443} | {'1': 2443} | {'TEMPORARILY_UNAVAILABLE': 2443} | 104.2 | 127.5 | 129.5 | 196.5 |
| recovery | collect | 305 | {'503': 1, '200': 304} | {'1': 1} | {'TEMPORARILY_UNAVAILABLE': 1} | 16.6 | 22.9 | 22.9 | 103.4 |
| recovery | probe | 61 | {'503': 1, '200': 60} | {'1': 1} | {'TEMPORARILY_UNAVAILABLE': 1} | 8.0 | 15.4 | 13.8 | 103.5 |
| recovery | heavy | 11 | {'503': 5, '200': 6} | {'1': 5} | {'TEMPORARILY_UNAVAILABLE': 5} | 115.5 | 123.3 | 123.3 | 123.3 |

- Recovery: first all-200 5 s window of collection starts 5 s after the overload phase ended; last 30 s: 186 collection/probe requests, 0 not 200, collection p95 23.1 ms (baseline 45.1 ms, limit 90.2 ms). Server state before/after: 2026-10-12T10:59:23.107865603Z 0 / 2026-10-12T10:59:23.107865603Z 0
- Storage reconciliation: {'requests': 6259, 'rows_for_run_ids': 51100, 'ok_requests': 2555, 'ok_requests_not_fully_stored': 0, 'shed_requests': 3704, 'rows_stored_for_shed_requests': 0, 'other_requests': 0, 'rows_stored_for_other_requests': 0, 'rows_for_ids_without_result': 0, 'duplicate_ids_in_db': 0}
- Aggregate check after the run: {'names_ok': True, 'days_ok': True, 'list_first_page_ok': True, 'sql_total': 100000, 'api_total': 100000}; seed rows after run: 100000

## Figures

`figure-data-latency.csv` holds every request (run_id, ep, phase, elapsed_s, duration_ms) for plotting. Graphs: `perf/report/` (static viewer of `perf/results/index.json`).
