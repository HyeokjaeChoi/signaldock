# 02. Demo seed script

Status: ready-for-agent
Type: task
Blocked by: 01

## Scope and completion criteria

Build `scripts/demo-seed.py` as spec decision D4 says.

1. Reads `tracking-plan/plan.json`. Uses a fixed RNG seed, so the same "now" gives the same events.
2. Timestamps are relative to now: the last 7 days, with more events in recent days. Time zone Asia/Seoul for the day boundaries.
3. Includes events that break the tracking plan and client reports, so the Dashboard shows those views.
4. Posts through `POST /v1/events:batch` to a base URL argument (default `http://127.0.0.1:8080`), with the collection key from the environment. Batches stay under the server limits (262144 bytes per batch; client report 4096 bytes). Fails with a clear message and non-zero exit on any non-2xx response.
5. Python standard library only. Runs with `python3 -I`.
6. A test: event generation is deterministic for a fixed "now" and seed, every event name and property follows the plan except the intended violations, and batch sizes stay under the limits. Add it where the repo runs script tests (check `.github/workflows/repo-rules.yml`).

Verification:
- The test command and its result.
- One run against the local compose stack, then the Dashboard shows the data (screenshot or `curl` of a query endpoint with the query key).
