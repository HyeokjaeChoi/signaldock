# Model tiers

`AGENTS.md` names tiers, not models. This file maps each work step to a tier and a Claude Code model. When a model changes, edit only this table.

| Step | Tier | Claude Code | Notes |
|---|---|---|---|
| Design, grilling, planning | top | the session itself | Conversational work. Do not delegate. |
| Implementation: product code, tests, build configuration, docs edits | mid | `sonnet` | Delegate to a subagent. |
| Local `/code-review` before a PR | top | `opus` | Only this step uses `opus` during implementation. The session model sets the model: run it in a session on `opus` (`/model`), or delegate it to a subagent with the Agent tool `model: opus` parameter. |
| Open the PR: description and `gh pr create` | mid | `sonnet` | |
| Research and lookups | low | `haiku` | Read-only. |

- Conversational work (grilling, design interviews) cannot be delegated: a subagent returns once and cannot talk to the owner. Run the session itself on the top tier.
- One-shot design analysis can go to a top-tier subagent while the session stays on a lower tier (Agent tool `model` parameter, or `model:` in `.claude/agents/*.md`).
- Claude Code `opusplan` uses the top tier only in plan mode. It does not cover grilling or design in normal mode.
- The GitHub review job `.github/workflows/claude-review.yml` uses `--model opus` (ADR-0041 Amendment, 2026-10-25).
- If the listed model fails or is not available, use the next higher tier. If a top-tier step (`opus`) fails or is not available, stop that step and report to the owner. Do not drop to a lower tier. Say so in the report.
