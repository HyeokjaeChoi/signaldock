# Postmortem: review findings missed before the merge of PR #95

Status: complete. Blameless format after the [Google SRE workbook](https://sre.google/workbook/postmortem-culture/).
Sources: `gh pr view 95`, `gh pr view 97`, `gh pr view 99` (read-only, checked 2026-10-15); [issue 37](../../../.scratch/signaldock-portfolio/issues/37-tracking-plan-codegen.md); [issue 38](../../../.scratch/signaldock-portfolio/issues/38-tracking-plan-review-followups.md); [AGENTS.md](../../../AGENTS.md) "Review workflow".

## Summary

The script that waited for the PR #95 review printed only the first 60 lines of the review output. A Minor finding of review round 4 and a Trivial nitpick were not seen before the merge.
CodeRabbit found them again on the records PR #97. We fixed them in PR #99 (issue 38). An earlier bug in the same script also hid pending reviews.

## Impact

- Two review findings were open at merge:
  - Minor: `validatePlan` in `tracking-plan/generate.mjs` checked values before types ("Check field types before validating their values").
  - Trivial: no test ran the demo adapter with the generated wrappers (adapter-boundary test).
- `main` held both until PR #99 merged. No release, user, or data was affected. The plan generator accepted an invalid plan or threw a `TypeError` for it ([issue 38](../../../.scratch/signaldock-portfolio/issues/38-tracking-plan-review-followups.md)).
- The merge rule in AGENTS.md (no Critical or Major open) was met. The process still hid a finding that it should have shown.

## Timeline (UTC)

| Time | Event | Source |
| --- | --- | --- |
| 2026-10-14 23:36:15 | PR #95 merged with the two findings open | `gh pr view 95` |
| 2026-10-14 23:36:39 | Records PR #97 opened | `gh pr view 97` |
| before 2026-10-15 00:42:38 | CodeRabbit on PR #97 noted the open history; we then found the two findings. The exact time was not recorded | commit `a2ec8a5` "Issue 37 review history; issue 38 for open PR #95 findings" |
| 2026-10-15 00:49:40 | PR #97 merged (issue 37 resolved, issue 38 created) | `gh pr view 97` |
| 2026-10-15 01:20:55 | PR #99 merged: field types checked first, adapter-boundary test added | `gh pr view 99` |

The wait-script bug with `wc -l` was fixed before this incident. Its date is not recorded.

## Trigger

A merge decision based on a review summary that was cut at 60 lines.

## Root cause and contributing factors

Root cause: the wait script showed a fixed head of the output, not the whole review. The merge decision used what the script showed.
Contributing factors:
- The review output is long. Nitpicks and comments outside the diff come late in it, so they were the part that was cut.
- AGENTS.md sets the merge criterion by severity. A Minor or lower finding does not block a merge, but it must be recorded as a local issue (review workflow item 5). Recording needs the finding to be seen first.
- An earlier bug in the same script: macOS `wc -l` pads its output with spaces, so the check `[ "$PEND" = 0 ]` never matched. The fix was `tr -d ' '`. This showed that the script's logic was not tested on the real platform.
- No step listed all unresolved review threads before the merge.
- The script had no test. It is shell glue that decides a merge.

## Detection

CodeRabbit on PR #97 noted the open review history. This was a lucky second reviewer, not a designed control.

## What went well

- The records PR was a second look at PR #95. The findings were not lost.
- Both findings were small. The fix and tests were done in one PR (#99).
- The local issue tracker held the follow-up (issue 38), as the review workflow requires.
- A review of PR #99 caught that ADR-0021 said the adapter test used only the public SDK API, and it was fixed before merge ([issue 38 comments](../../../.scratch/signaldock-portfolio/issues/38-tracking-plan-review-followups.md)).

## What went badly

- The merge went out with a partial view of the review.
- The script bug with `wc -l` was found by behavior, not by a test.
- The detection depended on another PR.

## Action items

| Item | Type | Status |
| --- | --- | --- |
| Fix the two findings (issue 38) | mitigate | done (PR #99) |
| Fix the `wc -l` padding with `tr -d ' '` | prevent | done |
| Print the full findings, including nitpicks and comments outside the diff, in the wait script | prevent | open (the script is not in the repo; no record of the change) |
| List all unresolved root review threads before the merge, and stop if any is unrecorded | detect | open |
| Record each open Minor or lower finding as a local issue before the merge, not after | prevent | open |
| Keep the wait script in the repo with a test that runs it on macOS and Linux output | prevent | open |

## Lessons

- A control that prints a cut of the data is not a control. Show all of it or show the count that was cut.
- A shell test that was never run on the target platform is a guess. `wc -l` differs between macOS and GNU.
- A second reviewer found what the first process hid. Do not rely on that. Add a check to the process ([AGENTS.md](../../../AGENTS.md) review workflow item 4).
