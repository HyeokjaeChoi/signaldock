# Postmortem: PR #95 closed unmerged after a failed merge call

Status: complete. Blameless format after the [Google SRE workbook](https://sre.google/workbook/postmortem-culture/).
Sources: `gh pr view 95` and the PR #95 timeline from the GitHub API (read-only, checked 2026-10-15); [issue 37 comments](../../../.scratch/signaldock/issues/37-tracking-plan-codegen.md).

## Summary

`gh pr merge 95 --squash` returned a GitHub GraphQL error. The same shell command then removed the worktree, the local branch, and the remote branch without a check of the merge state.
Deleting the remote head branch closed PR #95 without a merge. We restored the branch, reopened the PR, waited for CI, and merged. No code was lost.

## Impact

- PR #95 (issue 37, tracking plan codegen) was closed for 6 minutes 54 seconds and merged 10 minutes 39 seconds after the close.
- One extra push, one reopen, and one extra CI wait.
- No lost commit, no change on `main` that was not intended, no user-facing effect.
## Timeline (UTC, 2026-10-14)

| Time | Event | Source |
| --- | --- | --- |
| 14:50:40 | PR #95 opened | `gh pr view 95` |
| 23:25:28 | `gh pr merge 95 --squash` returned "Something went wrong while executing your query" (GraphQL) | session record (evidence gap: local agent transcript, not in the repo) |
| before 23:25:36 | Same command line deleted the worktree, the local branch, and the remote branch `impl/tracking-plan` | session record (evidence gap: local agent transcript, not in the repo) |
| 23:25:36 | PR #95 closed; `head_ref_deleted` | PR timeline |
| 23:32:29 | Head branch restored by a push of commit `0829679` from a new worktree | PR timeline |
| 23:32:30 | PR #95 reopened (`gh pr reopen 95`) | PR timeline |
| 23:32 to 23:36 | Waited for CI | session record (evidence gap: local agent transcript, not in the repo) |
| 23:36:15 | PR #95 merged; merge commit `ad19739` | `gh pr view 95` |
| 23:36:32 | Head branch deleted after the merge | PR timeline |

## Trigger

A GraphQL error from `gh pr merge`.

## Root cause and contributing factors

Root cause: the cleanup steps ran after the merge command whatever its result. The command line did not stop on the failure and did not check that the PR was merged.
Contributing factors:
- The merge call and the cleanup were one command line. A failure in the first did not block the second.
- The cleanup was not safe to run twice or after a partial failure. It deleted state that could not be re-created by running it again.
- Deleting the remote head branch closes an open PR. We did not have this written down as a risk in the workflow.
- GitHub gave an error for a call that may or may not have changed state. We did not read the PR state before we acted.
- The restore needed a push of the checked-out branch, because the pre-push hook requires it. We needed a new worktree to do that. This made recovery slower, not wrong.

## Detection

We saw the PR as closed in the PR state after the cleanup. The GraphQL error was visible at 23:25:28Z, 8 seconds before the close, but the script did not act on it.

## What went well

- The commit `0829679` was still reachable, so a re-push restored the branch.
- `gh pr reopen 95` worked after the branch came back.
- CI ran again on the reopened PR and the merge used the normal checks.
- Recovery took under 11 minutes.

## What went badly

- A destructive step depended on a command that had just failed.
- No check of the PR state before the cleanup.
- The recovery path (new worktree for the pre-push hook) was found during the incident.

## Action items

| Item | Type | Status |
| --- | --- | --- |
| Run the cleanup only after `gh pr view <n> --json state` returns `MERGED` | prevent | done |
| Keep the merge call and the cleanup as separate commands, and stop on a non-zero exit | prevent | open (not recorded as done in the repo) |
| Write in `docs/agents/issue-tracker.md` or the workflow notes that deleting a head branch closes an open PR | prevent | open |
| Keep the branch until the state check passes. A branch that exists can always be re-pushed | mitigate | open |

Only the first item is recorded as done. The repo has no script for it, so the status rests on this record.

## Lessons

- Do not run a destructive step after a command whose result you did not read.
- A tool error does not tell you whether the state changed. Read the state.
- The SRE workbook lists this rule: "All commands should be idempotent, or at least fail-safe on repeat invocations" ([postmortem culture](https://sre.google/workbook/postmortem-culture/)). The cleanup was not.
- Keep the last known good commit reachable until the work is confirmed done.
