# 02. Web: ESLint, Prettier, axe, bundle budget

Status: resolved
Type: task
Execution: Done (PR #283, 2026-11-15 UTC)

## Scope and completion criteria

Spec gates W1-W4. ADR-0049 decisions 2, 3.

1. ESLint flat config (`typescript-eslint` strict-type-checked, `react-hooks`, `jsx-a11y`) and Prettier `--check` for `dashboard/`, `perf/report/`, `web-shared/`. Exact versions in `package.json`, lockfiles committed.
2. axe-core tests in Vitest (jsdom) for each Dashboard view and the perf report page: fail on any `serious` or `critical` violation.
3. Bundle budget script (no new dependency): gzip size of `dist/**/*.js` vs a committed budget = first measured size + 10%.
4. All run in `scripts/verify-contract.sh` step 4 (CI `verify-contract`).
5. Do not edit `AGENTS.md`, `repo-rules.yml`, or `docs/design-index.md`; put row text for ticket 01 in the PR description.
6. ADR-0049 amendment: measured gzip sizes, the budget, axe results, run ID.
