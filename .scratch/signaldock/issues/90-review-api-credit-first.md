# 90. The PR review runs on API credits first and on the subscription second

Status: claimed
Type: task
Blocked by: None
Execution: PR pending

## Scope and completion criteria

On 2026-11-15 UTC the owner asked that the review job work like the `pleos-family-stop` repository (`.github/workflows/ai-review.yml`), so that a used-up subscription quota does not stop the review (several ADR-0049 PRs wait for a review round, AGENTS.md rule 7).

1. `.github/workflows/claude-review.yml`: trigger by the label `claude-review-request`; step 1 `claude -p` with `ANTHROPIC_API_KEY`; step 2 `claude-code-action` with `CLAUDE_CODE_OAUTH_TOKEN` only when step 1 had no key or failed with a used-up credit or rate limit and no summary with the marker exists (any other failure fails the job); a last step fails the job without a summary; the label is removed at the end. One `env` block holds the prompt, model, turn limit, and tool list.
2. `.github/renovate.json5`: a `customManagers` entry for `CLAUDE_CODE_VERSION`.
3. ADR-0041 gets a dated `## Amendment (2026-11-15)`. The original text is kept.
4. AGENTS.md rules 5 and 6, the `architecture.md` row for ADR-0041, and the ADR-0041 line of `docs/design-index.md` follow the amendment.

Notes:
- The owner sets the secret: `gh secret set ANTHROPIC_API_KEY --repo HyeokjaeChoi/signaldock`. `gh` cannot copy a secret value from another repository, and no agent handles the key. `CLAUDE_CODE_OAUTH_TOKEN` exists already. Until the API key exists, step 1 prints a warning and step 2 reviews.
- The label trigger replaces the push trigger, as in family-stop. Open PRs get no review until the label is added.
