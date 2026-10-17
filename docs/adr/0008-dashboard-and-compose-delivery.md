---
status: accepted
---

# Dashboard and Docker Compose delivery decisions

Issue 15 builds the Dashboard and the Compose entry point. Spec section 6 "Dashboard" and "Reproduction and deliverables", Q77, Q108, Q109, Q128 to Q131, ADR-0007 "Authentication" and "Left open", and `.scratch/signaldock/dashboard-verification-defaults.md` fix the behavior. These decisions fill the gaps those sources leave. They are implementer decisions that the user can change.

**Server bind address.** `main()` bound to `127.0.0.1` only. Inside a container, nginx cannot reach that address. The server now reads `SIGNALDOCK_BIND_HOST` (default `127.0.0.1`). The server image sets `0.0.0.0`. This is safe because Compose publishes the server port on the host's `127.0.0.1` only. The server still has no CORS.

**Compose layout.** One `docker-compose.yml`, no profiles. `docker compose up -d --build` starts `db`, `server`, and `dashboard`. DB-only mode is `docker compose up -d db`. Normal `stop`, `down`, and `restart` keep the named volume `signaldock-db`. The reset command is `docker compose down --volumes`. The server port and the DB port are published on `127.0.0.1` so that an app, the SDK, or a local Ktor run can reach them. An Android emulator reaches the host as `10.0.2.2`.

**nginx.** The official image renders `/etc/nginx/templates/default.conf.template` with the container environment at start, so `SIGNALDOCK_QUERY_KEY` is a runtime value of the nginx container and never a build argument or image content. `/v1/` accepts `GET` only (`limit_except GET`). The Dashboard has no reason to write, and a browser must not be able to send the query key to ingestion. nginx overwrites any `Authorization` header from the client.

**Project timezone in the Dashboard.** The Dashboard must pick period boundaries before it can read `timeZone` from a response. It uses the build-time value `VITE_SIGNALDOCK_TIME_ZONE`, default `Asia/Seoul`. Compose passes `SIGNALDOCK_TIME_ZONE` to the server (runtime) and to the Dashboard build (build argument). The value is not a secret. If `by-time` reports another zone, the Dashboard shows a notice.

**Polling details.** Q77 says only the latest first list page polls; other pages refresh by hand. The Dashboard follows Q77. A past page loads once and then refreshes on the Refresh button only. Tab return does not refresh it. Counts poll every 5 seconds. Details load once and have a separate Retry button. Q109 names retryable temporary failures. The Dashboard applies the 5, 10, 20, 30 second ladder to every failure that is not an explicit contract error, because a polling loop without a wait after a 4xx would hit the server every 5 seconds. Only `400` with `CONTRACT_VERSION_UNSUPPORTED` or `CONTRACT_HEADER_MISSING` stops polling (Q108). A new filter, period, sort, or cursor is a new query with its own failure count. Q109 does not persist retry state, and neither does this implementation.

**Development proxy.** The Vite dev server (`127.0.0.1`) proxies `/v1` to `SIGNALDOCK_SERVER_URL` or `http://127.0.0.1:${PORT:-8080}` and adds the Bearer header. It reads `SIGNALDOCK_QUERY_KEY` from `process.env`, and from the repository `.env` when `process.env` has no value. The value is never passed to `define`.

**Key check.** `npm run check-bundle-key` in `dashboard/` builds with a random key in the environment and fails if the key value or the name `SIGNALDOCK_QUERY_KEY` is in any output file. `scripts/verify-contract.sh` runs it, with the Dashboard tests.

**Dependency and image pins.** See TOOLCHAIN.md section "Build-verified in issue #15".
