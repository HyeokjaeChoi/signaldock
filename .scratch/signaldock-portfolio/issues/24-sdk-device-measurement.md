# 24. SDK device measurement (part of issue 08)

Status: ready-for-agent
Type: task
Blocked by: None
Execution: Not started

## Scope and completion criteria

Spec section 7 measurements on devices: synthetic events of about 1 KiB; normal 1 event/s for 60 s, offline accumulation of 1,000 events, burst 100 events/s for 10 s. Record device, OS, build mode, server environment, actual injection rate and call concurrency, `track()` completion p50/p95, errors, duplicates and missing events, queue drain time, DB and sidecar sizes, and memory change. Run on the minimum API (24) and the selected latest stable version (API 37).

Done when both AVDs have recorded raw results and a summary. Numbers are measurements in this environment, not an SLA; a run that misses the target injection rate is not reported as a pass.

## Comments

2026-10-12: Split from issue 08.
