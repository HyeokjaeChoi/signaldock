# 54. CodeQL advanced setup with a manual Kotlin build

Status: resolved
Type: task
Blocked by: None
Execution: Done (merged in PR #148, 2026-10-16 17:01:42 UTC)

## Scope and completion criteria

The CodeQL default setup failed for java-kotlin (Actions run 38918222644): autobuild could not pick one of `./contract-check`, `./sdk`, `./server`.

1. `.github/workflows/codeql.yml`: matrix `java-kotlin` (manual build), `javascript-typescript`, `python`, `actions`.
2. ADR-0032, `docs/design-index.md` entry, and a CodeQL line in `docs/engineering/threat-model.md`.
3. The main session turns the default setup off when the PR merges (GitHub rejects SARIF from an advanced setup while the default setup is on).

Verification: the workflow run on the PR (build steps); `scripts/check-repo-rules.sh`.

## Comments

- 2026-10-16 UTC: the CodeQL default setup was turned off before the merge (state not-configured), as completion criterion 3 requires.
- 2026-10-16 UTC: PR #148 merged 17:01:42Z.
- 2026-10-16 UTC: first run on `main`, workflow `codeql.yml` run 38936500321, concluded success. All four jobs succeeded. Code-scanning analyses on `refs/heads/main` (commit 625cfec): `java-kotlin` 8 results (17:09:58Z), `javascript-typescript` 25, `python` 18, `actions` 0, none with an error.
- ADR-0032 "Gaps" and `docs/engineering/threat-model.md` cite the run.
- Gap: the results are not triaged.
