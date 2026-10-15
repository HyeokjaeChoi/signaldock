---
status: accepted
---

# Server readiness check and restart policy (issue 45, failure mode 11)

Failure mode 11 (database down) had three gaps: no test stopped the database, `/health` said `ok` without a database check, and Compose gave the `server` service no health check and no restart policy. This ADR records the implementer decisions. The user can change them.

## Decisions

1. **`/health` stays liveness. A new `GET /ready` is readiness.** `/health` is used by `perf/seed.py`, `perf/run_one.py`, and `perf/overload.py` to wait for a start. Nothing else uses it: it is not in `openapi/contract-v1.yaml`, the Dashboard nginx template does not proxy it, and no architecture document names it. Its meaning stays "the process runs". Why: a restart cannot repair a database outage, so a liveness check that fails on the database would only restart a healthy server.
2. **`/ready` runs `SELECT 1` through `Database.ping()`.** It uses the normal path (admission, pool, statement timeouts), so it fails for the same reasons that make an ingest fail. It has a 2 s limit. It answers `200 ready`, or `503` with `Retry-After: 1` when the database is unavailable or the limit ends. It is not part of the contract: it is an operations route with a plain text body, and it needs no key.
3. **Compose.** `server` has a health check on `/ready` (every 5 s, 3 retries, 30 s start period) and `restart: unless-stopped`; `db` also gets `restart: unless-stopped`. The runtime image has no `curl`, so the check uses `bash` with `/dev/tcp`. Why restart: the server exits when the migration fails at start (the database is not reachable yet); restart lets it try again. A database outage after start does not stop the server (the pool reconnects), so the health state of `server` goes `unhealthy` and then `healthy` again without a restart. The Dashboard keeps `depends_on: server` without a health condition, so an unhealthy server does not block it.
4. **No alert and no metrics endpoint.** `unhealthy` is visible in `docker compose ps` only. Alerting stays out of scope (failure modes list).

## Test mechanism

`DatabaseOutageTest` (`server/app`, Testcontainers): it starts its own PostgreSQL container on a fixed host port, so a restart keeps the JDBC URL. It stops the container, then checks: `/ready` is 503 with `Retry-After`, `/health` is still 200, an ingest is 503 `TEMPORARILY_UNAVAILABLE` with `Retry-After`. It starts the container again and checks, with no server or `Database` restart: `/ready` returns 200, the resend of the refused event is accepted, and the table holds exactly the two accepted events.

Gap: the Compose health check and restart policy are checked with `docker compose config` only, not by a run of the stack with a stopped `db`.
