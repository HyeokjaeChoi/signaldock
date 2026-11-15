# k6 threshold proof (ADR-0049, amendment of ticket 04)

Written by `perf/threshold-proof/proof.py` on 2026-11-15. k6 v2.3.0 (commit/devel, go1.27.1, darwin/arm64); Python 3.9.6; macOS-27.2.1-arm64-arm-64bit.
Each case runs the real k6 script against a mock server with one fault. `exit` is the k6 exit status (99: a threshold was crossed). `crossed` lists the thresholds that `--summary-export` marks as crossed, with the observed value. `run script verdict` is what `common.k6_exit_reasons` makes of the k6 exit (the function `perf/run_one.py` and `perf/overload.py` use): a crossed server threshold is a `fail` reason that names it; a crossed `dropped_iterations` keeps the run `invalid` (generator limit). `match` compares the crossed set and that verdict with the expected ones (exact match).

| script | case | fault | k6 exit | crossed | run script verdict | Python summary parses the output | match |
|---|---|---|---:|---|---|---|---|
| load | clean | none | 0 | none | pass | run_one.read_k6: 156 requests, dropped 0 | ok |
| load | failures in warm-up only | every collect answers 500 in the first 4.5 s (warm-up is 5 s): the measure-phase submetric must not count them | 0 | none | pass | run_one.read_k6: 156 requests, dropped 0 | ok |
| load | http_req_failed | every 20th collect answers 500 | 99 | http_req_failed{phase:measure}: rate<0.01 (observed 0.04032) | fail | run_one.read_k6: 156 requests, dropped 0 | ok |
| load | dropped_iterations | 40 req/s, collect held 1.5 s at 12 s (all 30 VUs busy) | 99 | dropped_iterations: count==0 (observed 31) | invalid | run_one.read_k6: 999 requests, dropped 31 | ok |
| load | dropped_iterations and http_req_failed | as the case above, and every 20th collect answers 500 | 99 | dropped_iterations: count==0 (observed 30); http_req_failed{phase:measure}: rate<0.01 (observed 0.04654) | invalid, and a fail reason | run_one.read_k6: 1001 requests, dropped 30 | ok |
| overload | clean | every 2nd collect and by-name answer 503 in the overload phase | 0 | none | pass | overload.read_raw: 3589 requests, 902 collect 503 in overload | ok |
| overload | status other than 200 and 503 | every 50th collect answers 500 in the overload phase | 99 | unexpected_status: rate==0 (observed 0.01003) | fail | overload.read_raw: 3591 requests, 866 collect 503 in overload | ok |
| overload | status 0 (timeout) | every 100th collect answers after 6 s, past the 5 s k6 timeout | 99 | unexpected_status: rate==0 (observed 0.005013) | fail | overload.read_raw: 3591 requests, 884 collect 503 in overload | ok |
| overload | dropped_iterations | collect held 2.5 s at 8 s (all 300 VUs busy at 200 req/s) | 99 | dropped_iterations: count==0 (observed 205) | invalid | overload.read_raw: 3387 requests, 799 collect 503 in overload | ok |

## Replay on committed results (no new run)

The same thresholds read off result files that are already in the repository. `crossed` would be `yes` if the threshold had failed.

| file | script | shows | crossed |
|---|---|---|---|
| `perf/results/pilot-1/summary.json` | load.js | 0 of 2400 measure-phase requests not ok; dropped_iterations 0 | no |
| `perf/results/pilot-2/summary.json` | load.js | 0 of 2400 measure-phase requests not ok; dropped_iterations 0 | no |
| `perf/results/overload-1/summary.json` | overload.js | answer statuses ['200', '503']; dropped_iterations 0 | no |
