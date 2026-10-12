# 23. Server load measurement (part of issue 08)

Status: ready-for-agent
Type: task
Blocked by: None
Execution: Not started

## Scope and completion criteria

The server part of `.scratch/signaldock-portfolio/performance-plan.md`: fixed resource limits, seeds (10,000 and 100,000 events), collection load with k6 constant-arrival-rate (100 events/s), concurrent Dashboard queries, HikariCP pool comparison (2, 4, 8), duration, repetitions and run order, acceptance and selection, and the deliverables (per-run manifest, raw results, Markdown summary, figures). Local k6 only; no cloud upload.

Done when the harness runs the plan, the pilot is recorded, the measured runs and their verdicts are recorded with raw data, and invalid runs are kept with their reasons. A run that misses the target injection rate is not reported as a pass.

## Comments

2026-10-12: Split from issue 08.
