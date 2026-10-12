# 24. SDK device measurement (part of issue 08)

Status: ready-for-agent
Type: task
Blocked by: 23
Execution: Implemented and run on both AVDs (branch `impl/24-sdk-device-measurement`, 2026-10-12; not merged yet)

## Scope and completion criteria

Spec section 7 measurements on devices: synthetic events of about 1 KiB; normal 1 event/s for 60 s, offline accumulation of 1,000 events, burst 100 events/s for 10 s. Record device, OS, build mode, server environment, actual injection rate and call concurrency, `track()` completion p50/p95, errors, duplicates and missing events, queue drain time, DB and sidecar sizes, and memory change. Run on the minimum API (24) and the selected latest stable version (API 37).

Done when both AVDs have recorded raw results and a summary. Numbers are measurements in this environment, not an SLA; a run that misses the target injection rate is not reported as a pass.

## Comments

2026-10-12: Split from issue 08.

2026-10-12 (implementation, branch `impl/24-sdk-device-measurement`): harness, runs on both AVDs, summary. No product code changed. New decisions: [ADR-0014](../../../docs/adr/0014-sdk-device-measurement-harness.md).

Files:
- `sdk/sdk-android/src/androidTest/kotlin/local/signaldock/device/MeasurePhases.kt`: phases `normal`, `burst`, `offline_store`, `offline_drain` (skipped without `-e phase`). They use the public `SignalDock.initialize` path.
- `scripts/device-measure.sh <serial> [normal|offline|burst ...]`: runs the phases, pulls raw files, reads the stored event IDs from PostgreSQL, calls `perf/sdk_summarize.py`.
- `perf/sdk_summarize.py`: reconciliation, percentiles, verdict, Markdown.
- Results: `perf/sdk-results/<api>-<scenario>/` (raw `calls-*.csv`, `result-*.json`, `db-ids.txt`, `proxy.jsonl`, `meta.json`, `run.json`, `summary.md`) and `perf/sdk-results/summary.md`.
- Not done: `perf/report` and `perf/summarize.py --index` do not show SDK runs (cost: TypeScript and a second data shape; ADR-0014 "Not done").

Environment: Apple M2 Pro, 12 CPUs, macOS host that also ran other work (load average 5 to 14 during the runs; other agents used the host. A first set of runs, taken before the review fix that reads the event size earlier, gave the same pattern and is not kept, stored in each `meta.json`). AVDs `signaldock-api24` (Android 7.0, API 24, `google_apis` arm64) and `signaldock-api37` (Android 17, API 37, ps16k arm64), one at a time, `-no-window -no-audio -no-snapshot-save`. Build mode: debug androidTest APK (SDK library debug variant, debuggable, no R8). Server: real Ktor + PostgreSQL 17.6 from `docker-compose.yml`, Compose project `signaldock-sdkmeasure`, ports 18090 and 15440, product settings, behind the pass-through proxy `scripts/e2e/fault-proxy.py`. The runs used uncommitted files of this branch, so each `meta.json` says `(dirty)`.

Commands:
```
docker compose -p signaldock-sdkmeasure --env-file <env outside the repo> up -d --build db server
$ANDROID_HOME/emulator/emulator -avd signaldock-api37 -no-window -no-audio -no-snapshot-save -port 5584 &
SIGNALDOCK_ENV_FILE=<env> scripts/device-measure.sh emulator-5584
# then the same with signaldock-api24 on -port 5586 and emulator-5586
```

Results (one run per cell; measured in this environment, not an SLA; all 6 runs valid, target rates reached; verdict pass = no `track()` error, 0 missing, 0 duplicate rows, queue drained):

| AVD | Scenario | Target / actual events/s | Max in flight | track() p50 / p95 / max ms | Missing / dup | Drain s |
| --- | --- | --- | --- | --- | --- | --- |
| api24 | normal 60 s | 1 / 1.0 | 1 | 2.36 / 5.73 / 8.30 | 0 / 0 | 0.94 |
| api24 | offline 1,000 | back to back / 1075.4 | 1 | 0.78 / 1.89 / 12.89 | 0 / 0 | 16.24 (4.3 s retry wait, 11.97 s after) |
| api24 | burst 10 s | 100 / 100.0 | 3 | 1.18 / 2.46 / 26.17 | 0 / 0 | 0.06 |
| api37 | normal 60 s | 1 / 1.0 | 1 | 1.85 / 5.37 / 18.95 | 0 / 0 | 2.10 |
| api37 | offline 1,000 | back to back / 943.5 | 1 | 0.77 / 2.86 / 20.03 | 0 / 0 | 16.65 (2.2 s retry wait, 14.50 s after) |
| api37 | burst 10 s | 100 / 99.98 | 4 | 1.31 / 2.72 / 47.01 | 0 / 0 | 0.06 |

DB and sidecar sizes, and memory change. Run on the minimum API (24) and the selected latest stable version (API 37).

Done when both AVDs have recorded raw results and a summary. Numbers are measurements in this environment, not an SLA; a run that misses the target injection rate is not reported as a pass.

## Comments

2026-10-12: Split from issue 08.

2026-10-12 (implementation, branch `impl/24-sdk-device-measurement`): harness, runs on both AVDs, summary. No product code changed. New decisions: [ADR-0014](../../../docs/adr/0014-sdk-device-measurement-harness.md).

Files:
- `sdk/sdk-android/src/androidTest/kotlin/local/signaldock/device/MeasurePhases.kt`: phases `normal`, `burst`, `offline_store`, `offline_drain` (skipped without `-e phase`). They use the public `SignalDock.initialize` path.
- `scripts/device-measure.sh <serial> [normal|offline|burst ...]`: runs the phases, pulls raw files, reads the stored event IDs from PostgreSQL, calls `perf/sdk_summarize.py`.
- `perf/sdk_summarize.py`: reconciliation, percentiles, verdict, Markdown.
- Results: `perf/sdk-results/<api>-<scenario>/` (raw `calls-*.csv`, `result-*.json`, `db-ids.txt`, `proxy.jsonl`, `meta.json`, `run.json`, `summary.md`) and `perf/sdk-results/summary.md`.
- Not done: `perf/report` and `perf/summarize.py --index` do not show SDK runs (cost: TypeScript and a second data shape; ADR-0014 "Not done").

Environment: Apple M2 Pro, 12 CPUs, macOS host that also ran other work (load average 5 to 14 during the runs; other agents used the host. A first set of runs, taken before the review fix that reads the event size earlier, gave the same pattern and is not kept, stored in each `meta.json`). AVDs `signaldock-api24` (Android 7.0, API 24, `google_apis` arm64) and `signaldock-api37` (Android 17, API 37, ps16k arm64), one at a time, `-no-window -no-audio -no-snapshot-save`. Build mode: debug androidTest APK (SDK library debug variant, debuggable, no R8). Server: real Ktor + PostgreSQL 17.6 from `docker-compose.yml`, Compose project `signaldock-sdkmeasure`, ports 18090 and 15440, product settings, behind the pass-through proxy `scripts/e2e/fault-proxy.py`. The runs used uncommitted files of this branch, so each `meta.json` says `(dirty)`.

Commands:
```
docker compose -p signaldock-sdkmeasure --env-file <env outside the repo> up -d --build db server
$ANDROID_HOME/emulator/emulator -avd signaldock-api37 -no-window -no-audio -no-snapshot-save -port 5584 &
SIGNALDOCK_ENV_FILE=<env> scripts/device-measure.sh emulator-5584
# then the same with signaldock-api24 on -port 5586 and emulator-5586
```

Results (one run per cell; measured in this environment, not an SLA; all 6 runs valid, target rates reached; verdict pass = no `track()` error, 0 missing, 0 duplicate rows, queue drained):

| AVD | Scenario | Target / actual events/s | Max in flight | track() p50 / p95 / max ms | Missing / dup | Drain s |
| --- | --- | --- | --- | --- | --- | --- |
| api24 | normal 60 s | 1 / 1.0 | 1 | 1.83 / 3.52 / 6.22 | 0 / 0 | 0.99 |
| api24 | offline 1,000 | back to back / 1037.7 | 1 | 0.82 / 1.92 / 9.29 | 0 / 0 | 16.33 (3.9 s retry wait, 12.39 s after) |
| api24 | burst 10 s | 100 / 100.01 | 3 | 0.96 / 2.06 / 24.21 | 0 / 0 | 0.07 |
| api37 | normal 60 s | 1 / 1.0 | 1 | 2.34 / 7.09 / 30.93 | 0 / 0 | 1.51 |
| api37 | offline 1,000 | back to back / 928.7 | 1 | 0.81 / 2.79 / 16.45 | 0 / 0 | 16.55 (3.6 s retry wait, 12.96 s after) |
| api37 | burst 10 s | 100 / 100.0 | 3 | 0.84 / 2.04 / 28.65 | 0 / 0 | 0.06 |

DB and sidecar sizes, memory change, ACK statuses, and per-run details are in `perf/sdk-results/summary.md`. In every run each event was sent once (no ID sent twice) and every ACK was `accepted`. Delivery of the 1,000 offline events took about 13 s after the retry wait (20 batches, both AVDs); this follows the SDK's own Worker scheduling and was not analyzed further.
