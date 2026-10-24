---
status: accepted
---

# Claude Code review on pull requests (issue 83)

The user installed the Claude GitHub App on 2026-10-24 UTC and asked that Claude Code review each PR from now on. This ADR records how the review runs.

## Context

- CodeRabbit reviews each PR against the design sources (AGENTS.md "Judgment rules" 5, `.coderabbit.yaml`). Its local CLI has a review quota; on 2026-10-24 UTC it hit the rate limit twice and PR 201 opened without a local review.
- `anthropics/claude-code-action` v1 runs Claude Code in a GitHub Actions job. With a `prompt` input it runs once per event and posts PR comments and inline comments. Source: the action's `docs/solutions.md` ("Automatic PR Code Review").
- The action authenticates with an Anthropic API key or a Claude subscription OAuth token (`claude setup-token`). The repository owner has a Claude subscription.

## Decision

1. `.github/workflows/claude-review.yml` runs on `pull_request` `opened`, `synchronize`, `ready_for_review`, and `reopened`. A newer push cancels the running review of the same PR.
2. It skips drafts, PRs from forks (they get no secrets), and Dependabot and Renovate PRs (version bumps only; repo-rules CI checks them).
3. Authentication: the repository secret `CLAUDE_CODE_OAUTH_TOKEN`. The repository owner sets it; no agent handles the token.
4. Permissions are set on the job only: `contents: read`, `pull-requests: write`, `id-token: write`. The workflow default is `permissions: {}`, as in `pr-description.yml`.
5. The action is pinned by full commit SHA with a tag comment, like the other actions (`16f9f71ab9c490faf367f94dd294897edc25be33`, v1). Renovate proposes updates (ADR-0031).
6. The model is Sonnet, for cost. Allowed tools are read-only file tools, `gh pr diff`, `gh pr view`, `gh pr comment`, and inline comments. It cannot push.
7. The prompt asks for a review against the design sources (AGENTS.md, `docs/design-index.md` sections) with a severity per finding: Critical, Major, Minor, or Nit. Its findings are answered like CodeRabbit findings (AGENTS.md "Judgment rules" 5) and count for the merge rule (rule 7).

## Consequences

- Each PR gets a second reviewer. Cost: one Sonnet run per push on the owner's subscription quota.
- Until the secret is set, the job fails on each PR. It is not a required check, so it does not block merges.
- Rejected: replacing CodeRabbit. CodeRabbit keeps its path rules and learnings in `.coderabbit.yaml`; two reviewers with the same design sources catch more.
