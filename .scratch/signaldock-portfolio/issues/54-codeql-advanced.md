# 54. CodeQL advanced setup with a manual Kotlin build

Status: claimed
Type: task
Blocked by: None
Execution: Branch `ci/codeql-advanced`. ADR-0032, spec Q162.

## Scope and completion criteria

The CodeQL default setup failed for java-kotlin (Actions run 38918222644): autobuild could not pick one of `./contract-check`, `./sdk`, `./server`.

1. `.github/workflows/codeql.yml`: matrix `java-kotlin` (manual build), `javascript-typescript`, `python`, `actions`.
2. ADR-0032, `docs/design-index.md` entry, and a CodeQL line in `docs/engineering/threat-model.md`.
3. The main session turns the default setup off when the PR merges (GitHub rejects SARIF from an advanced setup while the default setup is on).

Verification: the workflow run on the PR (build steps); `scripts/check-repo-rules.sh`.
