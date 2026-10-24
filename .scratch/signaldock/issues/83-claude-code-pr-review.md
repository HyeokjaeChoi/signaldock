# 83. Replace CodeRabbit with a Claude Code review workflow

Status: claimed
Type: task
Blocked by: None
Execution: In progress (branch ci/claude-code-review)

## Scope and completion criteria

The user installed the Claude GitHub App on 2026-10-24 UTC, asked that Claude Code review each PR, and asked to disable CodeRabbit. ADR-0041 records the decision.

1. `.github/workflows/claude-review.yml`: `anthropics/claude-code-action` pinned by SHA, job-level permissions, skips drafts, forks, Dependabot, and Renovate. Auth through the `CLAUDE_CODE_OAUTH_TOKEN` secret, set by the owner.
2. The review rules from `.coderabbit.yaml` move into the workflow prompt; `.coderabbit.yaml` is deleted. The CodeRabbit app no longer has access to this repository (removed 2026-10-24 UTC).
3. AGENTS.md "Commands" and "Judgment rules" 5 and 6, and CONTRIBUTING.md, name the Claude review and the local `/code-review` skill. `docs/design-index.md` lists ADR-0041; `architecture.md` "Decisions and tradeoffs" has its row.
4. Records that name CodeRabbit (CHANGELOG, postmortems, issue files, older ADRs) do not change.

Verification:
- `scripts/check-repo-rules.sh`; the workflow YAML loads; local `/code-review`; the first PR after merge gets a Claude review.
