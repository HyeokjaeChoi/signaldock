# 03. Container hardening for the demo stack

Status: claimed
Type: task
Blocked by: 01

## Scope and completion criteria

Add the container part of spec decision D12 in `infra/compose.demo.yml`. The local `docker-compose.yml` does not change, except where a change is needed for both (state why in the PR).

1. Services run as non-root, with `cap_drop: ALL`, `no-new-privileges`, `read_only: true` and tmpfs for the paths that need writes, and memory, pids, and CPU limits.
2. The Dashboard image runs as non-root (for example from `nginxinc/nginx-unprivileged`, pinned by digest, Renovate keeps it current per ADR-0031). The Dashboard still injects the query key and proxies only `GET /v1/`.
3. PostgreSQL is on an `internal: true` network that only the server joins.
4. The server JVM gets `-XX:MaxRAMPercentage` (value from a measurement, written in the PR).
5. The override also selects GHCR images by `DEMO_TAG` (`ghcr.io/hyeokjaechoi/signaldock-server`, `-dashboard`), so issue 05 can use it. Builds stay possible without the override.
6. Confirm the spec section 7 items for this issue: `nginx-unprivileged` user and port, JVM with `noexec` on `/tmp`, `internal: true`, `pids_limit`.

Verification:
- `docker compose -f docker-compose.yml -f infra/compose.demo.yml config` is valid.
- The stack starts with the override (local images), `/ready` is 200, the Dashboard loads.
- From the Dashboard container, the DB host does not resolve or connect.
- `docker stats` once, recorded in the PR.
