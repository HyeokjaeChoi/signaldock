# 83. Add a Claude Code review workflow for pull requests

Status: claimed
Type: task
Blocked by: None
Execution: In progress (branch ci/claude-code-review)

## Scope and completion criteria

The user installed the Claude GitHub App on 2026-10-24 UTC and asked that Claude Code review each PR. ADR-0041 records the decision.

1. `.github/workflows/claude-review.yml`: `anthropics/claude-code-action` pinned by SHA, job-level permissions, skips drafts, forks, Dependabot, and Renovate. Auth through the `CLAUDE_CODE_OAUTH_TOKEN` secret.
2. `docs/design-index.md` lists ADR-0041 for the workflow; `architecture.md` "Decisions and tradeoffs" has its row. AGENTS.md "Judgment rules" 5 names both reviewers.
3. The repository owner sets the secret. No agent handles the token.

Verification:
- `scripts/check-repo-rules.sh`; the workflow parses (`actionlint` if present, else a YAML load); after the secret is set, the job posts a review on this PR.
