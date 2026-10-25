# 87. Record the model tier for each work step

Status: claimed
Type: task
Blocked by: None

## Scope and completion criteria

AGENTS.md said "a suitable low-cost model". It did not say which model does which step. The owner set the rule on 2026-10-25.

1. `docs/agents/models.md` maps each work step to a tier and a Claude Code model: implementation `sonnet`, local `/code-review` `opus`, opening the PR `sonnet`, research `haiku`, design and planning in the top-tier session.
2. AGENTS.md judgment rule 1 names the tiers and points to that file. A "Where things are" line sends the reader to the file before delegating.
3. The GitHub review job keeps `--model sonnet` (ADR-0041). This issue does not change it.

Verification:
- `bash scripts/check-repo-rules.sh`
