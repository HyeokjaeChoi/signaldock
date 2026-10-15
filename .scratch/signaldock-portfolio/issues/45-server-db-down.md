# 45. Server survives a database outage (failure mode 11)

Status: resolved
Type: task
Blocked by: None
Execution: Done (merged in PR #110, 2026-10-15 UTC)

## Scope and completion criteria

Failure mode 11: no test stops the database, `/health` has no database check, and Compose has no server health check or restart policy. The decisions are in ADR-0026.

1. `/health` stays liveness. `GET /ready` runs `SELECT 1` and returns 503 with `Retry-After` when the database is down.
2. `docker-compose.yml`: `server` health check on `/ready`; `restart: unless-stopped` on `server` and `db`. Earlier Compose fixes stay.
3. Integration test: stop PostgreSQL mid-run; ingest and `/ready` return 503, nothing is stored; after the restart both recover without a server restart.
4. Server test suite passes; `docker compose config` is valid.

## Comments

- 2026-10-15: CodeRabbit full review of PR #110 found no actionable comments.
- 2026-10-15: live Compose check on OrbStack with a separate project (`-p sdready`, volume removed after): server `healthy` and `/ready` 200; after `stop db`, server `unhealthy`, `/health` `ok`, `/ready` 503; after `start db`, `healthy` again with RestartCount 0. Recorded as a comment on PR #110. The restart policy itself was not triggered.
- 2026-10-15: merged after a `docs/design-index.md` conflict with PR #113 was resolved (both ADR lines kept).
