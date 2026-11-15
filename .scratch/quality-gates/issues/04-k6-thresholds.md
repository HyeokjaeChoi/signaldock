# 04. k6 thresholds

Status: ready-for-agent
Type: task

## Scope and completion criteria

Spec gate B2. ADR-0049, ADR-0013, ADR-0048.

1. `thresholds` in `perf/load.js`, `perf/overload.js`, `perf/demo-vm/collect.js`: `http_req_failed` rate < 1%, `dropped_iterations` count == 0, p95 bound from the script's stop rule. `overload.js` expects shedding: its thresholds state what overload must show (for example no 5xx other than 503), not zero errors.
2. The Python summaries keep working; a run that breaks a threshold exits 99 and the run scripts report it.
3. Prove each threshold fails with a local mock server (as in PR 274) and passes on a clean run.
4. Do not edit `AGENTS.md`, `repo-rules.yml`, or `docs/design-index.md`.
5. ADR-0049 amendment with the thresholds and the proof run.
