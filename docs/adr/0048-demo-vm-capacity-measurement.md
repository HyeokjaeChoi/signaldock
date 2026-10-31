---
status: accepted
---

# Capacity measurement of the live demo VM

The owner asked how much collection load the live demo accepts. This ADR records how it was measured and the result. It adds to [ADR-0013](0013-server-load-measurement-harness.md), which measured one Mac with Docker limits.

## Context

- The demo runs on an e2-medium VM (2 vCPU shared core, about 1 vCPU sustained, 4 GB) with the container limits of `infra/compose.demo.yml` ([ADR-0043](0043-cloud-demo-hosting-and-deploy.md), [ADR-0045](0045-cloud-demo-host-and-container-hardening.md)).
- Host nginx limits each IP to 5 r/s with burst 10 ([ADR-0044](0044-cloud-demo-operation-replaces-adr-0028.md)). A client outside the VM measures this limit and the WAN, not the server.

## Decision

1. Target: the demo exactly as deployed. The limits of `infra/compose.demo.yml` and the default server DB concurrency settings do not change. We do not measure an unconstrained server.
2. Load generator: k6 `grafana/k6:2.3.0`, pinned as `grafana/k6@sha256:2dd4756ec6e14e62778916e1eb850108e4b499304c02df56760fa0bdb0fba0b8`, in a container on the same VM with `--cpus 0.5 --memory 256m` and the host network. It calls `http://127.0.0.1:8080` and does not pass host nginx.
3. Stages: 5, 10, 20, 40, 80 req/s, 20 events of about 1 KiB per request (100 to 1,600 events/s). Each stage has 30 s warm-up and 180 s measure. 180 s lets the stage pass the burst of the e2 core. Collection API only.
4. Stop rule: non-200 share above 1%, p95 above 2x the p95 of the first stage (58.4 ms), `dropped_iterations` above 0, or k6 CPU max of 50% or more. A stage that hits a k6 condition is not valid as a server number.
5. Test events stay in the demo database until the daily 04:00 Asia/Seoul reset. A salt per stage in each `eventId` marks them. The k6 image and the work files were removed from the VM after the run.
6. Scripts and data: `perf/demo-vm/`. Results: `perf/results/demo-vm-20261031/`. `perf/demo-vm/report.py` writes `report.md` and the `demo_vm` list in `perf/results/index.json`. `perf/report/` shows `demo_vm`. `perf/summarize.py` skips directories without `manifest.json` and keeps `demo_vm` when it rewrites `index.json`, so its documented command still works.

### Alternatives rejected

- A. k6 on a laptop through the public URL: measures the nginx limit and the WAN.
- C. A second VM: needs a security configuration change and credit.
- D. An IAP tunnel: the tunnel is the bottleneck.

## Results

Report: [`perf/results/demo-vm-20261031/report.md`](../../perf/results/demo-vm-20261031/report.md).

| req/s | ok requests | p50 / p95 / p99 ms | Note |
|---:|---:|---|---|
| 5 | 901 | 19.9 / 29.2 / 44.1 | pass |
| 10 | 1,801 | 17.8 / 24.8 / 36.5 | pass |
| 20 | 3,601 | 17.7 / 25.8 / 54.3 | pass |
| 40 | 7,200 | 17.4 / 34.2 / 126.5 | pass; db CPU mean 40.8%, max 93% |
| 80 | 13,563 sent, 7,826 were 503 (57.7%) | p95 648.7 | invalid as a server latency number: k6 CPU max 87%, 843 dropped iterations |

## Consequences

- The sustained capacity of the demo as deployed is between 40 and 80 req/s (800 to 1,600 events/s). The likely bottleneck is the database (CPU near its limit at 40 req/s).
- At 80 req/s the server sheds overload with 503, as designed.
- The number is not an SLA. It is one VM, one day, and one load shape.
- Not measured: Dashboard queries during load, the public path through host nginx, other VM types, long runs, and rates between 40 and 80 req/s.
- `docker stats` CPU in a 5 s sample can be above the `--cpus` quota.
