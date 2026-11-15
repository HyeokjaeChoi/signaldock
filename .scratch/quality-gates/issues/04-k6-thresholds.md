# 04. k6 thresholds

Status: resolved
Type: task
Execution: Done (merged in PR #287, 2026-11-15 UTC)

## Scope and completion criteria

Spec gate B2. ADR-0049 decision 7, ADR-0013, ADR-0048.

1. `thresholds` in `perf/load.js` and `perf/overload.js` only: `http_req_failed` rate < 1% in `load.js`. `overload.js` has no `http_req_failed` threshold (it must shed with 503); it has a custom rate of answers with a status not in {200, 503}, status 0 (timeout, network error) included, with threshold `rate == 0` (ADR-0013 overload pass rule), `dropped_iterations` count == 0. No p95 threshold: the ADR-0013 p95 rules are relative to a baseline measured in the same run (`perf/overload.py:245`), so they stay in Python (ADR-0049 decision 7). Use measure-phase submetrics where the script tags phases.
2. `perf/demo-vm/collect.js` gets no thresholds (ADR-0048 finds capacity with failing stages; its verdict stays in Python).
3. `perf/run_one.py` and `perf/overload.py` treat k6 exit 99 as "threshold crossed": they summarize the run and record the crossed thresholds. A crossed `dropped_iterations` threshold keeps the run `invalid` (generator limit, `run_one.py:548-549`, `overload.py:256-257`). Other non-zero exits are invalid too.
4. Prove each threshold fails against a local mock server and passes on a clean run, and that exit 99 still produces a summary.
5. Do not edit `AGENTS.md`, `repo-rules.yml`, or `docs/design-index.md`.
6. ADR-0049 amendment with the thresholds and the proof run.
