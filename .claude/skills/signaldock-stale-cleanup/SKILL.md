---
name: signaldock-stale-cleanup
description: Find and fix stale docs and issue files in the Signaldock repo after merged work. Use for "stale 정리", "문서 정리", "이슈 정리", or after a batch of merges.
---

# Stale cleanup (SignalDock)

Run this after a batch of merges, or on request. It extends `update-internal-docs`: read `.claude/skills/update-internal-docs/SKILL.md` first and follow its source-of-truth and exclusion rules.

## Steps

1. Sync: `git fetch origin main`, start a branch from `origin/main`. Read `docs/agents/issue-tracker.md` and `docs/agents/triage-labels.md`.
2. Issue audit:
   - Count `Status:` values in `.scratch/*/issues/*.md`. Every `claimed` issue: find its PR (`Execution:` line, `git log origin/main --grep`). If the PR merged, set `Status: resolved` and `Execution: Done (PR number, date UTC)`.
   - Any issue whose `Execution:` starts with `Done` must be `resolved`.
   - A `Gap:` line that names an issue file with `Status: resolved`: update the status or evidence line.
3. Doc audit: for each recent merged PR (`git log origin/main`), grep the docs for the claim it changed ("not started", "pending", "Gap:", "no physical device", test counts, version strings). Check every relative Markdown link with `test -e`.
4. Decide per finding:
   - Fix: descriptive docs, status and evidence lines of `architecture.md`, issue status.
   - Do not change, report only: ADR text, released CHANGELOG sections, dated historical rows, research notes, issue records of other work.
   - Unsure: change nothing, list it in the report.
   - Prefer updating status over deleting.
5. Write one issue file for the cleanup. Pick the next free number after checking open PRs for new issue files (`git ls-remote`, PR file lists), so numbers do not collide. Set it to `resolved` in the same PR. Edit minimal lines. Run `bash scripts/check-repo-rules.sh` (check the real exit code) and the link check. `scripts/verify-contract.sh` is skipped for Markdown-only changes; say so in the PR.
6. Remote branches: list `git ls-remote --heads origin`; branches of merged PRs and branches with no activity for days are cleanup candidates. Delete only if allowed; otherwise report them.
7. Open one PR (attribution block first, one purpose). List fixed items, kept items with reasons, and steps not run. Merge when CI is green and no Critical or Major finding is open.
8. Write a report: found, changed, why, kept. Save it where the user asked.
