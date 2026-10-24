---
status: accepted
---

# Claude Code replaces CodeRabbit as the PR reviewer (issue 83)

The user installed the Claude GitHub App on 2026-10-24 UTC, asked that Claude Code review each PR from now on, and asked to disable CodeRabbit. This ADR records how the review runs. It replaces CodeRabbit in AGENTS.md "Judgment rules" 5 and 6 and the "Commands" pre-PR step.

## Context

- CodeRabbit reviewed each PR against the design sources with `.coderabbit.yaml` path rules. On 2026-10-24 UTC its local CLI hit the review rate limit twice, and the GitHub app skipped PRs 201 and 202: "This repository does not receive automatic reviews because it has fewer than 10 stars."
- `anthropics/claude-code-action` v1 runs Claude Code in a GitHub Actions job. With a `prompt` input it runs once per event and posts PR comments and inline comments. Source: the action's `docs/solutions.md` ("Automatic PR Code Review").
- The action authenticates with an Anthropic API key or a Claude subscription OAuth token (`claude setup-token`). The repository owner has a Claude subscription.
- The action skips a run when the workflow file on the PR differs from the default branch. So the PR that adds the workflow is not reviewed by it (PR 202 run: "Workflow validation failed").

## Decision

1. `.github/workflows/claude-review.yml` runs on `pull_request` `opened`, `synchronize`, `ready_for_review`, and `reopened`. A newer push cancels the running review of the same PR.
2. It skips drafts, PRs from forks (they get no secrets), and Dependabot and Renovate PRs (version bumps only; repo-rules CI checks them).
3. Authentication: the repository secret `CLAUDE_CODE_OAUTH_TOKEN`. The repository owner sets it; no agent handles the token.
4. Permissions are set on the job only: `contents: read`, `pull-requests: write`, `id-token: write`. The workflow default is `permissions: {}`, as in `pr-description.yml`.
5. The action is pinned by full commit SHA with a tag comment, like the other actions (`16f9f71ab9c490faf367f94dd294897edc25be33`, v1). Renovate proposes updates (ADR-0031).
6. The model is Sonnet, for cost. Allowed tools are read-only file tools, `gh pr diff`, `gh pr view`, `gh pr comment`, and inline comments. It cannot push.
7. The prompt carries the rules that `.coderabbit.yaml` held: review against the sections `docs/design-index.md` lists per area; report a defect only with a failing input or sequence; skip what the repo scripts and linters check; do not suggest a rejected alternative; skip generated code, Gradle wrappers, and lockfiles; per finding give the defect, a failure scenario, the source, a severity (Critical, Major, Minor, Nit), and a confidence.
8. CodeRabbit is removed: the app no longer has access to this repository (2026-10-24 UTC), and `.coderabbit.yaml` is deleted. Before a PR, an agent runs the local Claude Code `/code-review` skill instead of `coderabbit review`.

## Consequences

- One reviewer instead of two. Cost: one Sonnet run per push on the owner's subscription quota.
- Fork PRs get no automatic review; the owner reviews them by hand.
- A PR that changes `claude-review.yml` is never reviewed by the job (the file differs from `main`). Such a PR needs the local `/code-review` and an owner review before merge.
- A push cancels the running review of the older commit. Inline comments it already posted can stay on outdated lines.
- CodeRabbit learnings are lost. They held no design facts (old AGENTS.md rule 5), so no design source changes.
- Historical records that name CodeRabbit (CHANGELOG, postmortems, issue files, older ADRs) stay as they are.
