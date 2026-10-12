# Server load measurement summary

Source: `perf/summarize.py`. Values come from `summary.json` and `requests.csv` of each run. Targets are project targets set before measurement, not an SLA. Latency is HTTP start to completion as seen by k6 (sending + waiting + receiving); client connection wait is not included and is reported as `blocked+connecting max`.

## pilot-1

- Verdict: **pass** (pilot: does not count as a measured run)
- Combination: seed 100000 rows, recent70, window 30 d, 5 Dashboard clients, pool 4
- Git: `8d74285ca1de`, dirty=False, diff sha256 `e3b0c44298fc1c14`
- T: 2026-10-12T00:00:00+09:00; window 2026-09-11T15:00:00.000Z .. 2026-10-11T15:00:00.000Z; seed csv sha256 `b361ab8c7abe4f83`
- Warm-up 60 s, measurement 300 s; times: 2026-10-12T10:02:13.942Z to 2026-10-12T10:08:13.942Z

| Endpoint | samples | p50 ms | p95 ms | max ms | target p95 | outcomes |
|---|---|---|---|---|---|---|
| collect | 1500 | 18.7 | 27.2 | 163.0 | 500 | {'ok': 1500} |
| list | 300 | 55.8 | 146.9 | 167.4 | 500 | {'ok': 300} |
| by-name | 300 | 76.2 | 166.7 | 188.1 | 1000 | {'ok': 300} |
| by-time | 300 | 76.9 | 169.0 | 187.9 | 1000 | {'ok': 300} |

Per-minute trend in the measurement phase (n / p50 ms / p95 ms):

| Endpoint | min 1 | min 2 | min 3 | min 4 | min 5 | variable? |
|---|---|---|---|---|---|---|
| collect | 300 / 19.8 / 33.2 | 301 / 19.6 / 28.6 | 300 / 18.2 / 24.7 | 300 / 17.7 / 25.6 | 299 / 17.7 / 25.2 | no |
| list | 60 / 65.9 / 161.1 | 60 / 48.6 / 147.2 | 60 / 51.3 / 134.3 | 60 / 52.7 / 145.2 | 60 / 56.2 / 138.9 | no |
| by-name | 60 / 140.5 / 171.9 | 60 / 72.9 / 175.3 | 60 / 69.2 / 155.6 | 60 / 127.0 / 162.0 | 60 / 128.2 / 165.3 | no |
| by-time | 60 / 137.2 / 175.5 | 60 / 86.1 / 175.2 | 60 / 75.1 / 160.5 | 60 / 71.3 / 165.3 | 60 / 76.1 / 158.7 | no |

- Load validity: started 1500 of 1500 scheduled collection iterations, dropped_iterations=0
- Dashboard ticks (measurement): {"list": {"measure_ticks": 300.0, "measure_skipped": 0, "warmup_ticks": 60.0, "warmup_skipped": 0}, "by-name": {"measure_ticks": 300.0, "measure_skipped": 0, "warmup_ticks": 60.0, "warmup_skipped": 0}, "by-time": {"measure_ticks": 300.0, "measure_skipped": 0, "warmup_ticks": 60.0, "warmup_skipped": 0}}
- ACKs: {'acks_accepted': 36020.0, 'acks_duplicate': 0, 'acks_rejected': 0}
- Reconciliation: {'requests_with_result': 1801, 'rows_for_run_ids': 36020, 'ok_requests': 1801, 'ok_requests_not_fully_stored': 0, 'failed_requests': 0, 'rows_stored_after_failed_requests': 0, 'rows_for_ids_without_result': 0, 'unique_row_mismatch': 0, 'duplicate_ids_in_db': 0}
- Aggregate check: {'names_ok': True, 'days_ok': True, 'list_first_page_ok': True, 'sql_total': 100000, 'api_total': 100000}
- Rows added: 35940 (about 6220 by end of warm-up); WAL 45965549 bytes; events table 139370496 bytes, index 14237696 bytes
- Server stages in measurement: {"admission_wait": {"count": 2400, "p50_ms": 0, "p95_ms": 54, "max_ms": 138}, "connection_wait": {"count": 2400, "p50_ms": 0, "p95_ms": 71, "max_ms": 142}, "commit": {"count": 2400, "p50_ms": 13, "p95_ms": 145, "max_ms": 175}}
- Hikari counters in measurement (1 s samples): {'max_active': 4, 'max_pending': 4, 'samples': 298}
- Containers/generator in measurement: {"samples": 59, "k6_cpu_pct": {"avg": 1.6, "max": 7.1}, "host_cpu_pct_sum": {"avg": 187.4, "max": 349.6}, "db_cpu_pct": {"avg": 11.2, "max": 42.24}, "server_cpu_pct": {"avg": 7.3, "max": 22.19}, "db_mem_mib": {"avg": 157.7, "max": 165.5}, "server_mem_mib": {"avg": 192.1, "max": 213.4}, "k6_rss_kb": {"avg": 253340.5, "max": 253840}}
- GC: {'pause_count': 0, 'pause_total_ms': 0, 'pause_max_ms': 0}; cgroup throttling server: nr_throttled=4, throttled_usec=236811; db: nr_throttled=86
- Not collected: per-query DB execution time (no pg_stat_statements); Hikari acquire time other than the connection_wait stage; native JVM memory beyond container memory; physical disk I/O

## Figures

matplotlib is not installed here; wrote figure-data-latency.csv (run_id, ep, phase, elapsed_s, duration_ms) for later plotting.
