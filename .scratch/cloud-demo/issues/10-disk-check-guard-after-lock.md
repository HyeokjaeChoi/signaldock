# 10. Reset: re-check the 6 h guard after taking the lock

Status: resolved
Type: task
Blocked by: None
Execution: Done (PR #230, 2026-10-26 UTC)

## Scope and completion criteria

Minor finding from the PR 214 review (thread 4370283161), recorded under AGENTS.md rule 7.

`infra/vm/signaldock-reset.sh` checks disk use and the 6 h guard (`last-reset`) before `flock`. When the hourly disk check fires at 04:00 with the daily reset, it waits on the lock. After the daily reset ends, it runs a second reset right away and ignores the 6 h guard (ADR-0043 amendment, ticket 05).

1. On the `--if-disk-above` path, read `last-reset` and disk use again after the lock is taken. Exit 0 if disk use is below the limit. If disk use is still at or above the limit and a reset ran less than MIN_GAP ago, log the guard ERROR and exit 1, as before the lock (runbook section 13).
2. A case in the container harness.

Verification:
- The container harness for `infra/vm/` (command as in ticket 05 and the PR 214 description).
