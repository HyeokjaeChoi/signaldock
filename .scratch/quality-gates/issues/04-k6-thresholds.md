# 04. k6 thresholds

Status: ready-for-agent
Type: task

## Scope and completion criteria

Spec gate B2. ADR-0049 decision 7, ADR-0013, ADR-0048.

1. `thresholds` in `perf/load.js` and `perf/overload.js` only: `http_req_failed` rate < 1% (`overload.js`: no 5xx other than 503, since it must shed load), `dropped_iterations` count == 0, and a p95 bound from the script's stop rule. Use measure-phase submetrics where the script tags phases.
2. `perf/demo-vm/collect.js` gets no thresholds (ADR-0048 finds capacity with failing stages; its verdict stays in Python).
3. `perf/run_one.py` and `perf/overload.py` treat k6 exit 99 as "threshold crossed": they summarize the run and record the crossed thresholds. Only other non-zero exits make a run invalid.
4. Prove each threshold fails against a local mock server and passes on a clean run, and that exit 99 still produces a summary.
5. Do not edit `AGENTS.md`, `repo-rules.yml`, or `docs/design-index.md`.
6. ADR-0049 amendment with the thresholds and the proof run.
