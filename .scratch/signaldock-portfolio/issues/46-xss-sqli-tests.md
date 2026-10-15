# 46. XSS and SQL injection tests

Status: resolved
Type: task
Blocked by: None
Execution: Done (merged in PR #111, 2026-10-15 UTC)

## Scope and completion criteria

The threat model (branch docs/engineering-judgment) says the Dashboard shows free event names and property values from key holders with only React escaping, and that the server queries are typed jOOQ with "design claim only, no injection test was run". Tests only. No ADR needed.

1. XSS: `dashboard/src/xss.test.tsx` renders hostile names, property keys and values, SDK versions, and filter input through the real `App`, and an unknown plan status through `PlanBadge`. They must appear as text and inject no element. `scripts/check-repo-rules.sh` fails on `dangerouslySetInnerHTML`, `innerHTML`, `outerHTML`, or `insertAdjacentHTML` in `dashboard/src`, `perf/report/src`, and `web-shared` (test files exempt).
2. SQL injection: `IngestionIntegrationTest` stores hostile name, property key, and value verbatim and the table stays intact. `QueryRoutesTest` sends hostile text in every query parameter and the detail path: `name` matches exactly one row (list and all counts), other parameters return 400, and row count and stored values stay unchanged.

Gap: `perf/report` has no test runner, so only the static check covers it (its data comes from the repo's own perf run, not from key holders).

Verification:
- `cd dashboard && npm test && npm run typecheck`; `cd server && ./gradlew test verifyJooq --no-daemon`; `scripts/check-repo-rules.sh`.

## Comments

- 2026-10-15: local `coderabbit review` found that the detail-path assertion accepted a 404; fixed to require 400 `INVALID_PARAMETER`.
- 2026-10-15: PR #111 review had one Trivial nitpick: the ingest test used the same payload as event name and property key. Fixed with a distinct hostile key and value and an exact key-set assertion. The re-review had no actionable comments.
- Gaps kept: `perf/report` has no test runner (static check only); recharts axis labels are mocked in Dashboard tests.
