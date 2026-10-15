# 45. Server survives a database outage (failure mode 11)

Status: claimed
Type: task
Blocked by: None
Execution: Branch `impl/server-db-down`

## Scope and completion criteria

Failure mode 11: no test stops the database, `/health` has no database check, and Compose has no server health check or restart policy. The decisions are in ADR-0026.

1. `/health` stays liveness. `GET /ready` runs `SELECT 1` and returns 503 with `Retry-After` when the database is down.
2. `docker-compose.yml`: `server` health check on `/ready`; `restart: unless-stopped` on `server` and `db`. Earlier Compose fixes stay.
3. Integration test: stop PostgreSQL mid-run; ingest and `/ready` return 503, nothing is stored; after the restart both recover without a server restart.
4. Server test suite passes; `docker compose config` is valid.
