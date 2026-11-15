# Run scripts after a k6 exit 99 (ADR-0049, amendment of ticket 04)

Real runs of `perf/run_one.py` and `perf/overload.py` against the Docker stack of `perf/compose.perf.yml`, on 2026-11-15. k6 v2.3.0, Docker 29.8.2, macOS-27.2.1-arm64-arm-64bit. The tables are read from each run's `summary.json` and `k6-summary.json`; the run directories are not committed (they hold raw requests and host facts).

Commands. `run_one.py`: `python3 perf/run_one.py --seed-size 10000 --dist uniform --window 1 --clients 1 --pool 2 --warmup 10 --measure 30 --pilot --run-id <id> --out <dir>` (a short pilot). `overload.py`: `python3 perf/overload.py --run-id <id> --out <dir>` (the parameters of ADR-0013). The seeds came from `perf/seed.py build`.

Each experiment is a temporary edit of `perf/load.js` or `perf/overload.js` in the working copy, made only to cross a threshold on purpose. The files were restored after each run; none of the edits is committed. `k6 exit` is 99 when k6 reports a crossed threshold, else 0.

| runner | case | experiment | k6 exit | crossed thresholds | verdict | invalid_reasons | fail_reasons | summary, manifest, requests, k6 summary written |
|---|---|---|---:|---|---|---|---|---|
| run_one.py | clean | none: committed thresholds | 0 | none | pass | none | none | yes |
| run_one.py | server threshold | `http_req_failed{phase:measure}` limit changed to `rate<0` (a healthy server cannot meet it) | 99 | `http_req_failed{phase:measure}: rate<0` | fail | none | `k6 exit 99, thresholds crossed: ['http_req_failed{phase:measure}: rate<0']` | yes |
| run_one.py | generator limit | collect scenario `preAllocatedVUs: 1, maxVUs: 1` and `sleep(0.5)` in `collect()` | 99 | `dropped_iterations: count==0` | invalid | `k6 exit 99, generator threshold crossed: ['dropped_iterations: count==0'] (VU cap reached; a generator limit, not server behavior)`; `dropped_iterations=135 (generator VUs or generator saturation)`; `measurement started 15 of 150 scheduled collection iterations` | none | yes |
| overload.py | clean | none: committed thresholds | 0 | none | fail | none | `last 30 s of recovery contain non-200 answers`; `recovery collect p95 509.049 ms > 277.376 ms (2x baseline or +20 ms)` | yes |
| overload.py | server threshold | `unexpected_status` limit changed to `rate<0` | 99 | `unexpected_status: rate<0` | fail | none | `k6 exit 99, thresholds crossed: ['unexpected_status: rate<0']`; `baseline collect not all 200: {'200': 144, '503': 5}`; `baseline probe not all 200: {'200': 29, '503': 1}` | yes |
| overload.py | generator limit | collect scenario `preAllocatedVUs: 1, maxVUs: 1` | 99 | `dropped_iterations: count==0` | invalid | `k6 exit 99, generator threshold crossed: ['dropped_iterations: count==0'] (VU cap reached; a generator limit, not server behavior)`; `dropped_iterations=5538 (generator limit, not server behavior)` | `baseline collect not all 200: {'503': 1, '200': 145}` | yes |

Reading the table.
- A crossed server threshold gives `fail` and the run script still writes every result file. A crossed `dropped_iterations` gives `invalid` and adds no `fail` reason: a generator limit is not a server failure.
- The `overload.py` runs were made on a Mac shared with other running sessions (load average between 8 and 77 at the times it was read that day). Their `fail_reasons` that do not mention k6 (`baseline ... not all 200`, `recovery ... p95`) come from the Python pass rules of ADR-0013, not from a k6 threshold. The clean `overload.py` run had k6 exit 0 (`unexpected_status` stayed at 0 over 10,881 answers, with real 503 answers) and verdict `fail` from those Python rules, so it is not a clean pass of the whole run and is not used as one.
- These Docker runs were made before the review fixes of this PR. The only change to `perf/common.py` since is the case of exit 99 with no crossed threshold in the k6 summary (now `invalid`; covered by `perf/test_k6_thresholds.py`); no case in the table has it.
