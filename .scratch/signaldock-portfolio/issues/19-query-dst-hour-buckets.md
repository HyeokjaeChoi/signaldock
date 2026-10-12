# 19. Query: keep both repeated local hours at a DST fall-back

Status: ready-for-agent
Type: task
Blocked by: None
Execution: Done

## Scope and completion criteria

From the CodeRabbit Minor finding on PR #32 (issue 14), `server/persistence/.../QueryStore.kt` `countByTime`. PostgreSQL `date_trunc('hour', received_at, zone)` maps both occurrences of a repeated local hour to one instant. `bucketStarts` makes two buckets, so one shows 0 and the other the sum. The default zone `Asia/Seoul` has no DST, so the default setup is not affected.

For hour buckets, subtract the local minute and second remainder from `received_at` instead. Keep `date_trunc` for day buckets.

Done when a `QueryStoreTest` case in `America/New_York` across 2026-11-01 01:00-02:00 local counts each repeated hour on its own.

## Comments

2026-10-12: Recorded from the PR #32 review (Minor, merge not held, AGENTS.md "Review workflow" 5).

2026-10-12: Done.

- `QueryStore.countByTime`: hour buckets now use `received_at - ((received_at AT TIME ZONE zone) - date_trunc('hour', received_at AT TIME ZONE zone))`. The local minute/second/fraction remainder is subtracted from the instant, so both occurrences of a repeated local hour stay separate. Day buckets keep `date_trunc('day', received_at, zone)`.
- `:query` `bucketStarts`/`fillBuckets` needed no change: `ZonedDateTime.truncatedTo(HOURS)` keeps the current offset and `plusHours(1)` steps on the instant timeline, so the starts match the SQL (05:00Z and 06:00Z for New York on 2026-11-01).
- New test `QueryStoreTest` "hour buckets keep both repeated local hours at a DST fall-back": zone `America/New_York`, rows around 2026-11-01 01:00-02:00 local; expected buckets 04:00Z=1, 05:00Z=2, 06:00Z=3, 07:00Z=1. The existing `Asia/Seoul` tests pass unchanged.
- `(cd server && ./gradlew test verifyJooq --no-daemon)` with JAVA_HOME=`.tools/jdk-21.0.12.1+1` -> BUILD SUCCESSFUL; QueryStoreTest result file: tests=9, skipped=0, failures=0.
- `scripts/verify-contract.sh` -> `ALL CONTRACT CHECKS PASSED`.
