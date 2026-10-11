# 19. Query: keep both repeated local hours at a DST fall-back

Status: ready-for-agent
Type: task
Blocked by: None
Execution: Not started

## Scope and completion criteria

From the CodeRabbit Minor finding on PR #32 (issue 14), `server/persistence/.../QueryStore.kt` `countByTime`. PostgreSQL `date_trunc('hour', received_at, zone)` maps both occurrences of a repeated local hour to one instant. `bucketStarts` makes two buckets, so one shows 0 and the other the sum. The default zone `Asia/Seoul` has no DST, so the default setup is not affected.

For hour buckets, subtract the local minute and second remainder from `received_at` instead. Keep `date_trunc` for day buckets.

Done when a `QueryStoreTest` case in `America/New_York` across 2026-11-01 01:00-02:00 local counts each repeated hour on its own.

## Comments

2026-10-12: Recorded from the PR #32 review (Minor, merge not held, AGENTS.md "Review workflow" 5).
