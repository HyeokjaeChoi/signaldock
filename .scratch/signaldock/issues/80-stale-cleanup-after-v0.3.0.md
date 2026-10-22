# 80. Stale cleanup after SDK v0.3.0

Status: resolved
Type: task
Blocked by: None
Execution: Done (PR #198, 2026-10-22 UTC)

## Scope and completion criteria

Run `.claude/skills/signaldock-stale-cleanup/SKILL.md` after PRs #195, #196, and #197 (audit at `main` @ `5cd3bae`, 2026-10-22 UTC).

1. `TOOLCHAIN.md` line 69: the dashboard test count is 174 in 12 files (`cd dashboard && npx vitest run`), not 19.
2. `docs/engineering/README.md` "Refresh status": name PR #197, the only PR since the #195 refresh that changed these documents (`git diff a12d05b origin/main -- docs/engineering`; the other change in that diff is #195 itself).

Checked, no drift: version strings (0.3.0) in README*, integration guides, architecture, consumer app; all issue files `resolved`; relative Markdown links; script, workflow, and `SIGNALDOCK_*` references; CI job names; Gradle wrapper and Node image pins.

Not changed on purpose: `architecture.md` "Decisions and tradeoffs" has no rows for ADR-0037 to ADR-0040 (design source, reported in issue 77); AGENTS.md; ADR text; released CHANGELOG sections; `docs/releases/v0.*.md`; the `TOOLCHAIN.md` "Build-verified in issue #6" section (dated historical rows).

Verification:
- `scripts/check-repo-rules.sh`; every relative link in tracked Markdown files outside `.scratch/` exists. `scripts/verify-contract.sh` skipped: Markdown-only change.
