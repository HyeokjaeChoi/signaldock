# 76. Add the signaldock-stale-cleanup skill

Status: resolved
Type: task
Blocked by: None
Execution: Done (this PR, 2026-10-20 UTC)

## Scope and completion criteria

The stale cleanup of issue 75 (PR #189) followed a repeatable procedure. Keep it as a repo skill so the next run does the same steps.

1. Add `.claude/skills/signaldock-stale-cleanup/SKILL.md`. It extends `update-internal-docs`.
2. Add one line in `AGENTS.md` "Documentation upkeep" that names the skill.
3. No code change. No change to ADR text.

Verification:
- `scripts/check-repo-rules.sh`; every relative Markdown link exists.
