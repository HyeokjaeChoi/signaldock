# Live demo VM capacity measurement

This folder holds the load scripts for the live demo VM. The method and the reasons are in [ADR-0048](../../docs/adr/0048-demo-vm-capacity-measurement.md). The results are in [`perf/results/demo-vm-20261031/report.md`](../results/demo-vm-20261031/report.md).

## Purpose

Measure how many collection requests per second the demo accepts as deployed. `perf/report/` shows the numbers in a table and a chart (section "Live demo VM capacity").

## Decisions (interview with the owner, 2026-10-31)

1. Target: the demo exactly as deployed. The limits in `infra/compose.demo.yml` stay as they are (server 1.0 CPU and 512 MB, db 1.0 CPU and 384 MB, dashboard 0.5 CPU and 64 MB). The server uses its default DB concurrency settings. The VM is an e2-medium (2 vCPU shared core, about 1 vCPU sustained, 4 GB).
2. Load generator: k6 in a container on the same VM, `--cpus 0.5 --memory 256m`, image `grafana/k6:2.3.0` pinned as `grafana/k6@sha256:2dd4756ec6e14e62778916e1eb850108e4b499304c02df56760fa0bdb0fba0b8`. Rejected: a laptop through the public URL (measures the nginx limit and the WAN), a second VM (needs a firewall change and credit), an IAP tunnel (the tunnel is the bottleneck).
3. Stages: 5, 10, 20, 40, 80 req/s (100 to 1,600 events/s). Each request has 20 events of about 1 KiB. Each stage runs 30 s warm-up and 180 s measure. 180 s passes the burst of the e2 core. Collection API only.
4. Stop rule: the non-200 share is above 1%, p95 is above 2x the p95 of the first stage, `dropped_iterations` is above 0, or the CPU max of k6 is 50% or more. A stage that hits a k6 condition is not valid as a server number.
5. Test events stay in the demo database until the daily 04:00 Asia/Seoul reset. A salt per stage in each `eventId` marks them. After the run, the k6 image and the work files are removed from the VM.

## How to run

Run from the repository root. `<vm>` is the demo VM. Use your own `gcloud` settings.

1. Copy the files:
   `gcloud compute scp perf/demo-vm/collect.js perf/demo-vm/run-stage.sh <vm>:/tmp/sd-perf/`
2. Make the request templates and copy them:
   `python3 perf/demo-vm/gen_templates.py /tmp/templates.jsonl` then `gcloud compute scp /tmp/templates.jsonl <vm>:/tmp/sd-perf/`
3. On the VM, run one stage at a time, from low to high rate:
   `sudo K6_IMAGE=grafana/k6@sha256:2dd4756ec6e14e62778916e1eb850108e4b499304c02df56760fa0bdb0fba0b8 bash /tmp/sd-perf/run-stage.sh <rate> /tmp/sd-perf`
   The script reads the collection key from `/opt/signaldock/.env`. It never writes the key. k6 calls `http://127.0.0.1:8080`, so host nginx and its rate limit are not in the path. `docker stats` samples every container every 5 s.
4. Copy `/tmp/sd-perf/rate-<rate>/` to `perf/results/demo-vm-<date>/` and run `python3 perf/demo-vm/summarize.py perf/results/demo-vm-<date>/rate-<rate>`. This writes `stage.json`.
5. Apply the stop rule after each stage. Stop at the first stage that hits it.
6. Build the report and the index entry: `python3 perf/demo-vm/report.py perf/results/demo-vm-<date>`. Then run `npm run build` in `perf/report`.
7. Clean up on the VM: `sudo rm -rf /tmp/sd-perf` and `sudo docker rmi grafana/k6@sha256:...`.

`docker stats` CPU percent in a 5 s sample can be above the `--cpus` quota.

## Not measured

- Dashboard query load during collection.
- The public path: host nginx (rate limit 5 r/s, burst 10 per IP) and the WAN.
- Other VM types, long runs, and request rates between the stages (for example 60 req/s).
- Mobile SDK behavior. This is a server capacity number, not an SLA.
