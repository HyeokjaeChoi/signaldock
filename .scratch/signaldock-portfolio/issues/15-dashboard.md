# 15. Receipt verification Dashboard and Docker Compose (part of issue 05)

Status: ready-for-agent
Type: task
Blocked by: 14
Execution: Not started

## Scope and completion criteria

React Dashboard in `dashboard/` on the generated TypeScript models and the issue 14 API. Spec section 6 "Dashboard" and "Reproduction and deliverables": 5 s polling while visible, no overlapping identical queries, error with last data and last-success time, Q109 wait, no auto-jump to the latest page. Follow `.scratch/signaldock-portfolio/dashboard-verification-defaults.md` for the delegated UX defaults. One Docker Compose command starts the server, PostgreSQL, and the built Dashboard; a DB-only mode for local development; a distinct reset command; the DB volume survives restarts.

Query key access (spec Q128 to Q131, ADR-0007 "Authentication" and "Left open"): the browser bundle never holds a key. In Docker Compose, nginx serves the built Dashboard and proxies `/v1/*` to the server, adding `Authorization: Bearer ${SIGNALDOCK_QUERY_KEY}`. In development, the Vite dev server proxy does the same with the key from `process.env`. The Dashboard port binds to `127.0.0.1` only. The server has no CORS. Add `.env.example` with two different example keys (`SIGNALDOCK_COLLECTION_KEY`, `SIGNALDOCK_QUERY_KEY`); the real `.env` is gitignored. Compose passes both to the server, which refuses to start if the query key is missing, blank, or equal to the collection key.

Done when the Dashboard builds and its tests pass, and `docker compose up` serves it against the real server, with commands and results recorded here.

## Comments

2026-10-12: Split from issue 05.

2026-10-12: Scope extended by Q128 to Q131 (separate query key). The nginx and Vite proxy work, the `127.0.0.1` binding, and `.env.example` belong here. Issue 14 (PR #32) implements only the server side: `SIGNALDOCK_QUERY_KEY`, 401 `QUERY_KEY_INVALID`.
