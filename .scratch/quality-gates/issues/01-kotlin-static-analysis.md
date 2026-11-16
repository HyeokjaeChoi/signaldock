# 01. Kotlin static analysis: Android Lint, detekt with ktlint rules

Status: resolved
Type: task
Execution: Done (PR #284, 2026-11-16 UTC)

## Scope and completion criteria

Spec gates A1, A2, B1, D3. ADR-0049 decisions 2, 3.

1. Android Lint for `sdk/sdk-android` (and the demo modules that `sdk-test` builds): `warningsAsErrors`, `abortOnError`, committed `lint-baseline.xml`. Runs in CI job `sdk-test`.
2. detekt with the `formatting` plugin (ktlint rules) for `sdk/` and `server/` modules, `buildUponDefaultConfig`, committed baseline per module. Runs in `sdk-test` and `server-test`.
3. A `scripts/check-repo-rules.sh` check: a lint or detekt baseline may only lose entries vs `origin/main`. Prove it fails on a sample that adds an entry.
4. `.github/workflows/claude-review.yml:47` names only tools that run after this PR (detekt; shellcheck only if ticket 03 merged first).
5. New Gradle plugins pinned in version catalogs; `scripts/update-gradle-verification.sh` run and metadata committed (ADR-0038).
6. `AGENTS.md` enforced-rules rows for this ticket. This ticket owns `AGENTS.md`, `repo-rules.yml`, and `docs/design-index.md` in wave 1.
7. ADR-0049 dated amendment: findings count in each baseline, job time before and after.
