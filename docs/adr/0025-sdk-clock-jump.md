---
status: accepted
---

# SDK retry wait and a wall clock that moves (issue 44)

ADR-0006 items 8 and 9 store the next send time as a wall-clock value (`now + wait`). If the device clock moves back, for example by one day, the stored time is far in the future and sending waits for the size of the jump. No test covered this. This ADR adds a clamp. It is an implementer decision that the user can change.

## Decisions

1. **Store the wait with the time.** `retry_state` has a new column `waitMillis`: the wait that was meant when `nextAttemptAtMillis` was stored. It is the `wait` of ADR-0006 item 8 (hold: `max(30 minutes, Retry-After)`), item 9 (backoff: `max(draw, Retry-After, 30 minutes during a hold)`), and a 413 split (`Retry-After`, if valid). The ACK transaction clears it with the time. The schema stays at version 1, because no SDK is released (ADR-0003 item 7). The exported schema is updated.
2. **Clamp when the sender reads the state.** If `nextAttemptAtMillis > now + waitMillis`, the sender sets `nextAttemptAtMillis = now + waitMillis` and saves it before it decides. The clock then moved back, because a normal wait never exceeds `now + waitMillis`. The sender saves the clamp, so the next read does not move the time forward again.
3. **Retry-After is still never capped.** The clamp bound is the stored wait, which already includes a valid Retry-After (spec Q58, Q67, Q124; ADR-0006 item 5). A 2-hour Retry-After stays 2 hours after a jump back. Only the jump is removed. A fixed upper bound (5 minutes, 30 minutes) was rejected, because it would shorten a valid Retry-After.
4. **A row with no `waitMillis` is not clamped.** The value is null when no wait is stored.

## Limits

- Gap: a clock moved forward cannot be told from a long wait. The next attempt then comes early. The cost is one batch: a failure stores a new wait, so there is no burst. This is tested.
- Gap: the clamp runs when the sender runs. A WorkManager request made before the jump may fire late (WorkManager uses the wall clock too). The periodic work and the foreground triggers run the sender again, so the delay is bounded by their intervals. Not tested on a device.
- Gap: `Diagnostics.nextAttemptEpochMillis` shows the stored time until the sender clamps it.
- Out of scope: a monotonic clock (`elapsedRealtime`) with a boot id. It would detect forward jumps too, but needs reboot handling. Revisit if forward jumps matter.
