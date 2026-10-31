# Live demo VM capacity (2026-10-31)

Run: `demo-vm-20261031`. Method and decisions: [ADR-0048](../../../docs/adr/0048-demo-vm-capacity-measurement.md) and [perf/demo-vm/README.md](../../demo-vm/README.md).
This file is written by `python3 perf/demo-vm/report.py perf/results/demo-vm-20261031`. Do not edit it by hand.

Target: e2-medium (2 vCPU shared core, 4 GB), demo as deployed (infra/compose.demo.yml limits). Load: k6 on the same VM, 20 events (about 1 KiB each) per request, 30 s warm-up and 180 s measure per stage, collection API only.
Stop rule: non-200 share above 1%, p95 above 2x the first stage (29.2 ms), dropped iterations above 0, or k6 CPU max at or above 50%.
`pass` = no rule hit. `fail` = the server hit a rule. `invalid` = k6 itself was at its limit, so the stage is not a server latency number.

## Stages

| req/s | events/s | requests | ok | other | p50 ms | p95 ms | p99 ms | max ms | dropped | verdict |
|---:|---:|---:|---:|---|---:|---:|---:|---:|---:|---|
| 5 | 100 | 901 | 901 | - | 19.9 | 29.2 | 44.1 | 184.8 | 0 | pass |
| 10 | 200 | 1801 | 1801 | - | 17.8 | 24.8 | 36.5 | 191.0 | 0 | pass |
| 20 | 400 | 3601 | 3601 | - | 17.7 | 25.8 | 54.3 | 178.3 | 0 | pass |
| 40 | 800 | 7200 | 7200 | - | 17.4 | 34.2 | 126.5 | 231.0 | 0 | pass |
| 80 | 1600 | 13563 | 5737 | http_503 7826 | 340.3 | 648.7 | 857.6 | 1275.3 | 843 | invalid |

CPU in percent of one CPU, mean / max of 5 s `docker stats` samples (a sample can exceed the `--cpus` quota).

| req/s | server | db | dashboard | sd-k6 |
|---:|---:|---:|---:|---:|
| 5 | 11.3 / 39.2 | 7.6 / 11.7 | 0.4 / 5.1 | 2.2 / 4.1 |
| 10 | 14.6 / 37.8 | 10.9 / 14.9 | 0.5 / 5.1 | 3.2 / 5.6 |
| 20 | 17.9 / 26.9 | 21.5 / 67.9 | 0.8 / 5.1 | 6.2 / 7.8 |
| 40 | 33.2 / 42.5 | 40.8 / 93.0 | 0.3 / 5.2 | 11.8 / 14.8 |
| 80 | 94.5 / 145.5 | 92.7 / 168.7 | 1.3 / 9.2 | 39.7 / 87.2 |

## Verdict per stage

- 5 req/s: **pass**
- 10 req/s: **pass**
- 20 req/s: **pass**
- 40 req/s: **pass**
- 80 req/s: **invalid** (non-200 share above 1%; p95 above 2x first stage; dropped iterations; k6 CPU max at or above 50%)

## Conclusion

- The sustained capacity of this demo configuration is between 40 and 80 req/s (800 to 1,600 events/s).
- Up to 40 req/s: no non-200 answer, p95 34.2 ms or less. At 40 req/s the p99 grows to 126.5 ms.
- The database is the likely bottleneck: its CPU mean is 40.8% and its max is 93% at 40 req/s, near its 1.0 CPU limit. The server mean is 33.2%.
- At 80 req/s k6 hit its own limit (CPU max 87%, dropped iterations 843), so the latency of this stage is not a server number. The server answered 57.7% of the requests with 503. This shows that it sheds overload as designed.
- The 40 to 80 req/s range is not narrowed. A stage at 60 req/s was not run.

## Not measured

Dashboard queries during load, the public path through host nginx (its rate limit is 5 r/s with burst 10 per IP), the WAN, other VM types, and long runs. The test events stay in the demo database until the daily 04:00 Asia/Seoul reset.
