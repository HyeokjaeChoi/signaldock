# 03. shellcheck and actionlint

Status: claimed
Type: task
Execution: PR pending (branch gate/03-shell-workflow-lint)

## Scope and completion criteria

Spec gates D1, D2. ADR-0049.

1. shellcheck and actionlint pinned by version and SHA-256 in `scripts/setup-toolchain.sh` (or a check-only installer next to it).
2. shellcheck `--severity=warning` on every tracked `*.sh` and `.githooks/*`; actionlint on `.github/workflows`. Fix every finding; no blanket disables. Each `# shellcheck disable=` has a reason.
3. Runs in CI job `check` and in `.githooks/pre-commit` (through `scripts/check-repo-rules.sh` or a new script).
4. Do not edit Gradle, web, `AGENTS.md`, `repo-rules.yml` beyond the `check` job, or `docs/design-index.md`; put row text for ticket 01 in the PR description.
5. ADR-0049 amendment: findings fixed per script, run ID.
