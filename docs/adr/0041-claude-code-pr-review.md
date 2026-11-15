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

## Amendment (2026-10-25)

The owner changed the review model to `opus` for review quality. Cost is one Opus run per push on the owner's subscription quota. Decision item 6 and the first Consequences bullet now read as Opus. The original text above is kept as written.

## Amendment (2026-11-15)

The owner asked for the review flow of the `pleos-family-stop` repository (`.github/workflows/ai-review.yml`): review on API credits, with the subscription as fallback when the quota is used up. Decision items 1, 2, 3, and 4, and the Consequences bullets about cost and push, now read as follows. The original text above is kept as written.

1. Trigger: `pull_request` `labeled`, only for the label `claude-review-request`. A push starts no review. The last step removes the label; adding it again after the job ended reviews the next push (a label that is still on the PR sends no event). The job timeout is 60 minutes: step 1 25, step 2 the rest. The concurrency group is per PR and label. Drafts and Dependabot and Renovate PRs are no longer skipped, because a label is an explicit request. Fork PRs are still skipped (no secrets).
2. Step 1: `claude -p` (the Claude Code CLI, installed with npm at the exact version `CLAUDE_CODE_VERSION`, which Renovate raises) with the secret `ANTHROPIC_API_KEY`, `--model opus --max-turns 80`. The CLI runs directly because the family-stop workflow records that `claude-code-action` runs count as "Claude Code usage", which cannot use the subscription promo credit (not verified here).
3. Step 2: `claude-code-action` (SHA pin unchanged) with `CLAUDE_CODE_OAUTH_TOKEN` and `github_token`. It runs only if no summary comment for the head SHA exists after step 1. It does not match error text: a missing key, used-up credit, a rate limit, or a CLI failure that leaves no summary all lead to step 2, so the detection does not depend on error messages that the CLI does not document.
4. The summary comment starts with `<!-- claude-review:<head sha> -->`. Only a comment of `github-actions[bot]` with this marker, created after the start of this run, counts; a summary of an earlier run for the same SHA does not hide a failed review. So a step 1 that fails after it posted does not cause a second review.
5. A last step fails the job when no summary exists after both steps. A green job without a review would hide the failure.
6. Both steps use one `env` block for the prompt, `opus`, the turn limit, and the base tool list. Their prompt suffixes differ in how a finding on a line is posted; step 2 also reads the earlier line comments of `github-actions[bot]` to avoid repeats. Step 1 sets `CLAUDE_CODE_SUBPROCESS_ENV_SCRUB` so `gh` and the shell do not get the API key; a tool rule such as `Bash(gh pr comment:*)` still allows any arguments, so the review stays limited to same-repository PRs (fork PRs are skipped). Step 1 has its own 25-minute timeout; a job timeout would skip the last two steps and leave the label (then remove it by hand).
7. The owner sets both secrets with `gh secret set`; no agent handles them. Without `ANTHROPIC_API_KEY`, step 1 prints a warning and step 2 reviews.
8. Before step 1 the job restores `.claude/`, `CLAUDE.md`, `CLAUDE.local.md`, and `.mcp.json` from the PR base commit, so a PR cannot run its own Claude Code hooks or settings with the secrets. `CLAUDE_CODE_DISABLE_BACKGROUND_TASKS=1` is set for both steps.
9. Job permissions: `contents: read`, `pull-requests: write`, `issues: write` (label removal). `id-token: write` is gone: with `github_token` the action does not exchange an OIDC token (action docs, `src/github/token.ts`).

Consequences:

- CodeQL rule `actions/untrusted-checkout/medium` flags the checkout of the PR head in `claude-review.yml`. The review must read the PR code, so the checkout stays. Limits: the job runs only for PRs from this repository (no fork code), only after a user with write access adds the label, the prompt holds no PR text, Claude Code config is restored from the base, and the tools are read-only plus `gh` comments. The owner dismisses the alert as "won't fix" with this reason.
- Cost: one Opus run per label on API credits. The subscription quota is used only when step 1 leaves no summary.
- AGENTS.md rules 5 and 6 now say "add the label". An open PR gets no review until someone adds the label.
- The Consequences bullet about `claude-review.yml` changes holds only for step 2 (the action). Step 1 has no such check; whether step 2 keeps it with `github_token` is not verified.
