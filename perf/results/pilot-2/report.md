# Server load measurement summary

Source: `perf/summarize.py`. Values come from `summary.json` and `requests.csv` of each run. Targets are project targets set before measurement, not an SLA. Latency is HTTP start to completion as seen by k6 (sending + waiting + receiving); client connection wait is not included and is reported as `blocked+connecting max`.

## pilot-2

- Verdict: **pass** (pilot: does not count as a measured run)
- Combination: seed 100000 rows, recent70, window 30 d, 5 Dashboard clients, pool 4
- Git: `d8e4bc2e24cd`, dirty=True, diff sha256 `88697f3d8d457259`
- T: 2026-10-12T00:00:00+09:00; window 2026-09-11T15:00:00.000Z .. 2026-10-11T15:00:00.000Z; seed csv sha256 `b361ab8c7abe4f83`
- Warm-up 60 s, measurement 300 s; times: 2026-10-12T10:22:44.280Z to 2026-10-12T10:28:44.280Z

| Endpoint | samples | p50 ms | p95 ms | max ms | target p95 | outcomes |
|---|---|---|---|---|---|---|
| collect | 1500 | 19.1 | 30.2 | 169.6 | 500 | {'ok': 1500} |
| list | 300 | 53.5 | 144.8 | 161.4 | 500 | {'ok': 300} |
| by-name | 300 | 131.0 | 170.4 | 231.3 | 1000 | {'ok': 300} |
| by-time | 300 | 121.3 | 168.5 | 183.5 | 1000 | {'ok': 300} |

Per-minute trend in the measurement phase (n / p50 ms / p95 ms):

| Endpoint | min 1 | min 2 | min 3 | min 4 | min 5 | variable? |
|---|---|---|---|---|---|---|
| collect | 300 / 20.4 / 29.6 | 300 / 19.2 / 48.3 | 300 / 19.0 / 30.6 | 300 / 18.2 / 29.2 | 300 / 18.5 / 30.8 | no |
| list | 60 / 57.0 / 151.4 | 60 / 42.8 / 146.3 | 60 / 49.5 / 134.1 | 60 / 60.7 / 146.3 | 60 / 48.3 / 139.9 | no |
| by-name | 60 / 138.9 / 171.7 | 60 / 134.7 / 170.9 | 60 / 73.9 / 162.1 | 60 / 133.8 / 168.8 | 60 / 133.3 / 220.7 | no |
| by-time | 60 / 135.8 / 170.2 | 60 / 71.8 / 167.6 | 60 / 71.1 / 148.8 | 60 / 79.9 / 170.3 | 60 / 133.3 / 169.4 | no |

- Load validity: started 1500 of 1500 scheduled collection iterations, dropped_iterations=0
- Dashboard ticks (measurement): {"list": {"measure_ticks": 300.0, "measure_skipped": 0, "warmup_ticks": 60.0, "warmup_skipped": 0}, "by-name": {"measure_ticks": 300.0, "measure_skipped": 0, "warmup_ticks": 60.0, "warmup_skipped": 0}, "by-time": {"measure_ticks": 300.0, "measure_skipped": 0, "warmup_ticks": 60.0, "warmup_skipped": 0}}
- ACKs: {'acks_accepted': 36020.0, 'acks_duplicate': 0, 'acks_rejected': 0}
- Reconciliation: {'requests_with_result': 1801, 'rows_for_run_ids': 36020, 'ok_requests': 1801, 'ok_requests_not_fully_stored': 0, 'failed_requests': 0, 'rows_stored_after_failed_requests': 0, 'rows_for_ids_without_result': 0, 'unique_row_mismatch': 0, 'duplicate_ids_in_db': 0}
- Aggregate check: {'names_ok': True, 'days_ok': True, 'list_first_page_ok': True, 'sql_total': 100000, 'api_total': 100000}
- Rows added: 35940 (about 6220 by end of warm-up); WAL 47051546 bytes; events table 139378688 bytes, index 15163392 bytes
- Server stages in measurement: {"admission_wait": {"count": 2400, "p50_ms": 0, "p95_ms": 53, "max_ms": 136}, "connection_wait": {"count": 2400, "p50_ms": 0, "p95_ms": 75, "max_ms": 141}, "commit": {"count": 2400, "p50_ms": 13, "p95_ms": 146, "max_ms": 227}}
- Hikari counters in measurement (1 s samples): {'max_active': 4, 'max_pending': 4, 'samples': 298}
- Containers/generator in measurement: {"samples": 59, "k6_cpu_pct": {"avg": 2.1, "max": 18.5}, "host_cpu_pct_sum": {"avg": 224.9, "max": 441.5}, "db_cpu_pct": {"avg": 11.9, "max": 44.63}, "server_cpu_pct": {"avg": 6.9, "max": 17.46}, "db_mem_mib": {"avg": 159.9, "max": 162.0}, "server_mem_mib": {"avg": 198.7, "max": 217.1}, "k6_rss_kb": {"avg": 266830.4, "max": 267488}}
- GC: {'pause_count': 0, 'pause_total_ms': 0, 'pause_max_ms': 0}; cgroup throttling server: nr_throttled=1, throttled_usec=125817; db: nr_throttled=91
- Not collected: Hikari acquire time other than the connection_wait stage; native JVM memory beyond container memory; physical disk I/O

## Figures

`figure-data-latency.csv` holds every request (run_id, ep, phase, elapsed_s, duration_ms) for plotting. Graphs: `perf/report/` (static viewer of `perf/results/index.json`).
