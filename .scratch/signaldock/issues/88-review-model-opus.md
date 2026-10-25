# 88. The PR review job uses the opus model

Status: claimed
Type: task
Blocked by: None

## Scope and completion criteria

On 2026-10-25 UTC the owner asked that the GitHub PR review job use `opus` instead of `sonnet`, for review quality.

1. `.github/workflows/claude-review.yml`: `--model sonnet` becomes `--model opus`. Nothing else in the file changes.
2. ADR-0041 gets a dated `## Amendment (2026-10-25)`. The original decision text is kept (`docs/agents/doc-audit.md`, "Do not change").
3. The `architecture.md` row for ADR-0041 says Opus (summary of the amendment).

Notes:
- ADR-0041: a PR that changes `claude-review.yml` is not reviewed by the job. This PR needs the local `/code-review` and an owner review before merge.
- `docs/agents/models.md` is in another open PR and is not edited here. Its review-job line must change to `opus` after both PRs merge.

Verification:
- `bash scripts/check-repo-rules.sh`; `grep -n "model" .github/workflows/claude-review.yml` shows `--model opus`.
