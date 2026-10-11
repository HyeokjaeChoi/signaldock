# 15. Receipt verification Dashboard and Docker Compose (part of issue 05)

Status: ready-for-agent
Type: task
Blocked by: 14
Execution: Not started

## Scope and completion criteria

React Dashboard in `dashboard/` on the generated TypeScript models and the issue 14 API. Spec section 6 "Dashboard" and "Reproduction and deliverables": 5 s polling while visible, no overlapping identical queries, error with last data and last-success time, Q109 wait, no auto-jump to the latest page. One Docker Compose command starts the server, PostgreSQL, and the built Dashboard; a DB-only mode for local development; a distinct reset command; the DB volume survives restarts.

Done when the Dashboard builds and its tests pass, and `docker compose up` serves it against the real server, with commands and results recorded here.

## Comments

2026-10-12: Split from issue 05.
