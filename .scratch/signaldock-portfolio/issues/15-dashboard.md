# 15. Receipt verification Dashboard and Docker Compose (part of issue 05)

Status: ready-for-agent
Type: task
Blocked by: 14
Execution: Done (merged in PR #38, 2026-10-12)

## Scope and completion criteria

React Dashboard in `dashboard/` on the generated TypeScript models and the issue 14 API. Spec section 6 "Dashboard" and "Reproduction and deliverables": 5 s polling while visible, no overlapping identical queries, error with last data and last-success time, Q109 wait, no auto-jump to the latest page. Follow `.scratch/signaldock-portfolio/dashboard-verification-defaults.md` for the delegated UX defaults. One Docker Compose command starts the server, PostgreSQL, and the built Dashboard; a DB-only mode for local development; a distinct reset command; the DB volume survives restarts.

Query key access (spec Q128 to Q131, ADR-0007 "Authentication" and "Left open"): the browser bundle never holds a key. In Docker Compose, nginx serves the built Dashboard and proxies `/v1/*` to the server, adding `Authorization: Bearer ${SIGNALDOCK_QUERY_KEY}`. In development, the Vite dev server proxy does the same with the key from `process.env`. The Dashboard port binds to `127.0.0.1` only. The server has no CORS. Add `.env.example` with two different example keys (`SIGNALDOCK_COLLECTION_KEY`, `SIGNALDOCK_QUERY_KEY`); the real `.env` is gitignored. Compose passes both to the server, which refuses to start if the query key is missing, blank, or equal to the collection key.

Done when the Dashboard builds and its tests pass, and `docker compose up` serves it against the real server, with commands and results recorded here.

## Comments

2026-10-12: Split from issue 05.

2026-10-12: Scope extended by Q128 to Q131 (separate query key). The nginx and Vite proxy work, the `127.0.0.1` binding, and `.env.example` belong here. Issue 14 (PR #32) implements only the server side: `SIGNALDOCK_QUERY_KEY`, 401 `QUERY_KEY_INVALID`.

2026-10-12 (implementation, branch `impl/15-dashboard`, not pushed, no PR yet):

Design sections followed: architecture.md "Components and responsibilities", "DB time budgets and queries", "Contracts, generated sources, and JSON"; spec section 6 "Dashboard" and "Reproduction and deliverables", Q77, Q108, Q109, Q128 to Q131; `dashboard-verification-defaults.md` items 1 to 10; ADR-0002; ADR-0007 "Authentication" and "Left open". New decisions are in ADR-0008 (server bind address, Compose layout, GET-only nginx proxy, build-time timezone, polling ladder for all non-contract errors, dev proxy, key check).

Module and file map:
- `dashboard/src/polling.ts`: `PollingQuery` (5 s poll while visible, one in-flight request, Q109 ladder, Retry-After, Q108 stop, manual-only mode for past pages) and `usePolledQuery`.
- `dashboard/src/api.ts`: same-origin fetch with `SignalDock-Contract-Version: 1`, parsed with the generated `generated/typescript` converters. No key.
- `dashboard/src/period.ts`: calendar periods in the project timezone, 30-date limit.
- `dashboard/src/App.tsx`, `Details.tsx`, `Charts.tsx`: list, filters, paging, details, charts.
- `dashboard/vite.config.mts`: dev proxy adds the Bearer query key from the environment. `dashboard/scripts/check-bundle-key.mjs`: bundle check.
- `dashboard/Dockerfile`, `dashboard/nginx.conf.template`, `server/Dockerfile`, `docker-compose.yml`, `.dockerignore`, `.env.example`.
- `server/app/.../Application.kt`: `SIGNALDOCK_BIND_HOST` (default `127.0.0.1`).
- `scripts/verify-contract.sh`: now also runs `npm test` and `npm run check-bundle-key`. TOOLCHAIN.md: pins for issue #15.

Commands and results (JDK 21.0.12.1+1 from `.tools`; Node v24.14.1 locally, Docker Node image 24.20.0):
- In `dashboard/`: `npm ci`, `npm run typecheck` (TypeScript 7.0.2), `npm test` (3 files, 19 tests pass), `npm run contract-check`, `npm run build`, `npm run check-bundle-key` ("ok: query key not in 3 bundle files"): all pass.
- `scripts/verify-contract.sh`: ALL CONTRACT CHECKS PASSED. `(cd server && ./gradlew test verifyJooq --no-daemon)`: 62 tests, 0 failures, no jOOQ diff.
- `docker compose up -d --build` with `.env` copied from `.env.example` (not committed): db, server, dashboard started.
- Five events sent to `POST /v1/events:batch` with the collection key: all `accepted`. Through `http://127.0.0.1:5173/v1/events` and `/v1/event-counts/by-name` with no Authorization header: 200 with the events and counts (product_viewed 3, add_to_cart 1, purchase 1). A client `Authorization` header sent to nginx is overwritten (200). `POST` through nginx: 403. Direct `GET http://127.0.0.1:8080/v1/events` with the collection key: 401 `QUERY_KEY_INVALID`. The nginx html directory has no query key value.
- `docker compose restart`, then `down` + `up -d`: counts unchanged. `down --volumes` + `up -d`: counts empty. `docker compose up -d db`: only the db service runs. `npm run dev` with `SIGNALDOCK_QUERY_KEY` in the environment: `/v1/event-counts/by-name` through the Vite proxy returned 200.
- Screenshot: `.scratch/signaldock-portfolio/evidence/15-dashboard-list.png` (headless Chrome, Compose stack, list and both charts). No details screenshot: headless Chrome here cannot click; details are covered by `App.test.tsx`.
- `coderabbit review --base main --deep --agent`: 2 findings. Rejected: Gradle wrapper "missing" (tracked in `server/gradle/wrapper`, and the image build ran). Deferred: the PR description must name the ADR-0008 decisions (no PR opened yet).

Limits: no browser-level test of the full page; Dashboard tests use a fake `Api`, jsdom, and mocked charts. A `Today` period that rolls over to a new date while a past page is shown is not special-cased. Query time budgets stay undecided (ADR-0007). The hidden-tab rule is covered by unit tests only, not checked by hand in a browser. Compose was verified on macOS with OrbStack only.

2026-10-12: Record reconciled. Merged into main in PR #38 on 2026-10-12. Earlier branch and push notes above describe the state before that merge.
