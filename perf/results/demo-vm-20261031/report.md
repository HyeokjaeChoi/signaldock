# Live demo VM capacity (2026-10-31)

Run: `demo-vm-20261031`. Method and decisions: [ADR-0048](../../../docs/adr/0048-demo-vm-capacity-measurement.md) and [perf/demo-vm/README.md](../../demo-vm/README.md).
This file is written by `python3 perf/demo-vm/report.py perf/results/demo-vm-20261031`. Do not edit it by hand.

Target: e2-medium (2 vCPU shared core, 4 GB), demo as deployed (infra/compose.demo.yml limits). Load: k6 on the same VM, 20 events (about 1 KiB each) per request, 30 s warm-up and 180 s measure per stage, collection API only.
Rules: non-200 share above 1%, p95 above 2x the first stage (29.2 ms), or dropped iterations above 0 (the 50 VU cap was reached because the server is slow) = a server rule hit.
`pass` = no rule hit. `fail` = a server rule hit and k6 was under its limit. `invalid` = the k6 CPU mean in the measure window was at or above 45% (90% of its quota), so the stage is not a server latency number. `server rule hit` is shown for every stage, also for `invalid` ones.

## Stages

| req/s | events/s | requests | ok | other | p50 ms | p95 ms | p99 ms | max ms | dropped | server rule hit | verdict |
|---:|---:|---:|---:|---|---:|---:|---:|---:|---:|---|---|
| 5 | 100 | 901 | 901 | - | 19.9 | 29.2 | 44.1 | 184.8 | 0 | no | pass |
| 10 | 200 | 1801 | 1801 | - | 17.8 | 24.8 | 36.5 | 191.0 | 0 | no | pass |
| 20 | 400 | 3601 | 3601 | - | 17.7 | 25.8 | 54.3 | 178.3 | 0 | no | pass |
| 40 | 800 | 7200 | 7200 | - | 17.4 | 34.2 | 126.5 | 231.0 | 0 | no | pass |
| 80 | 1600 | 13563 | 5737 | http_503 7826 | 340.3 | 648.7 | 857.6 | 1275.3 | 843 | yes | fail |

CPU in percent of one CPU, mean / max of `docker stats` samples (about 7 s apart) in the measure window only (a sample can exceed the `--cpus` quota). The k6 limit check uses the k6 mean in this window.

| req/s | server | db | dashboard | sd-k6 |
|---:|---:|---:|---:|---:|
| 5 | 9.8 / 39.2 | 8.2 / 11.7 | 0.5 / 5.1 | 2.2 / 4.1 |
| 10 | 14.9 / 37.8 | 11.1 / 14.9 | 0.4 / 5.1 | 3.2 / 5.6 |
| 20 | 17.7 / 26.9 | 21.5 / 67.9 | 1.0 / 5.1 | 6.2 / 7.8 |
| 40 | 33.8 / 42.5 | 41.8 / 93.0 | 0.4 / 5.2 | 11.6 / 13.0 |
| 80 | 98.3 / 145.5 | 97.3 / 168.7 | 1.3 / 9.2 | 41.5 / 87.2 |

## Verdict per stage

- 5 req/s: **pass**
- 10 req/s: **pass**
- 20 req/s: **pass**
- 40 req/s: **pass**
- 80 req/s: **fail** (non-200 share above 1%; p95 above 2x first stage; dropped iterations (50 VU cap reached))

## Conclusion

- Highest `pass` rate: 40 req/s (800 events/s), p95 34.2 ms, p99 126.5 ms, non-200 share 0.0%.
- CPU at that stage (percent of one CPU, measure window): db 41.8% mean, 93.0% max; server 33.8% mean, 42.5% max.
- Lowest rate that is not `pass`: 80 req/s, verdict `fail`, server rule hit: yes, non-200 share 57.7%.
- The capacity of this configuration is between 40 and 80 req/s. Rates in between were not run.
- An `invalid` stage is not a server latency number. Its `server rule hit` column still shows whether the server shed load.

## Not measured

Dashboard queries during load, the public path through host nginx (its rate limit is 5 r/s with burst 10 per IP), the WAN, other VM types, and long runs. The test events stay in the demo database until the daily 04:00 Asia/Seoul reset.
