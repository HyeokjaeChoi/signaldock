---
status: accepted
---

# SDK retry wait and a wall clock that moves (issue 44)

ADR-0006 items 8 and 9 store the next send time as a wall-clock value (`now + wait`). If the device clock moves back, for example by one day, the stored time is far in the future and sending waits for the size of the jump. No test covered this. This ADR adds a clamp. It is an implementer decision that the user can change.

## Decisions

1. **Store the wait with the time.** `retry_state` has a new column `waitMillis`: the wait that was meant when `nextAttemptAtMillis` was stored. It is the `wait` of ADR-0006 item 8 (hold: `max(30 minutes, Retry-After)`), item 9 (backoff: `max(draw, Retry-After, 30 minutes during a hold)`), and a 413 split (`Retry-After`, if valid). The ACK transaction clears it with the time. The database version is now 2, with `MIGRATION_1_2` (`ALTER TABLE retry_state ADD COLUMN waitMillis INTEGER`). The reason: v0.1.0 (released 2026-10-14) shipped schema 1, so the premise "no SDK is released" of ADR-0003 item 7 no longer holds. `1.json` is unchanged, `2.json` is new, and the builder has no destructive fallback. `MigrationTest` builds a schema 1 database from `1.json` and checks that the events and the retry state survive and that `waitMillis` is null.
2. **Clamp when the sender reads the state.** If `nextAttemptAtMillis > now + waitMillis`, the sender sets `nextAttemptAtMillis = now + waitMillis` and saves it before it decides. The clock then moved back, because a normal wait never exceeds `now + waitMillis`. The sender saves the clamp, so the next read does not move the time forward again.
3. **Retry-After is still never capped.** The clamp bound is the stored wait, which already includes a valid Retry-After (spec Q58, Q67, Q124; ADR-0006 item 5). A 2-hour Retry-After stays 2 hours after a jump back. Only the jump is removed. A fixed upper bound (5 minutes, 30 minutes) was rejected, because it would shorten a valid Retry-After.
4. **A row with no `waitMillis` is not clamped.** The value is null when no wait is stored.
5. **Released schema files are frozen.** After v0.1.0, a schema change needs a new version, a migration, and a migration test. `scripts/check-repo-rules.sh` fails if a schema JSON that exists on `origin/main` changes.

## Limits

- Gap: a clock moved forward cannot be told from a long wait. The next attempt then comes early. The cost is one batch: a failure stores a new wait, so there is no burst. This is tested.
- Gap: the clamp runs when the sender runs. A WorkManager request made before the jump may fire late (WorkManager uses the wall clock too). The periodic work and the foreground triggers run the sender again, so the delay is bounded by their intervals. Not tested on a device.
- Gap: `Diagnostics.nextAttemptEpochMillis` shows the stored time until the sender clamps it.
- Gap: the schema freeze check needs `origin/main` in the checkout. It is skipped when `origin/main` is missing (a shallow CI checkout). It runs in the pre-commit hook. Changing the CI checkout is a separate PR.
- Out of scope: a monotonic clock (`elapsedRealtime`) with a boot id. It would detect forward jumps too, but needs reboot handling. Revisit if forward jumps matter.
