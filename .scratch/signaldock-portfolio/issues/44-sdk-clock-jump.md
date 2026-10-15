# 44. SDK retry wait survives a wall-clock jump

Status: claimed
Type: task
Blocked by: None
Execution: Branch `impl/sdk-clock-jump`, ADR-0025.

## Scope and completion criteria

The SDK stores the next send time as a wall-clock value. A clock moved back delays sending by the size of the jump (failure-modes row 6). Decisions are in ADR-0025.

1. The sender clamps the stored next attempt time to `now + stored wait` and saves the clamp. A valid Retry-After is still never capped.
2. Tests: clock back by one day after a failure, a hold, and a long Retry-After; clock forward by one day (one extra send, no burst); a row without a stored wait is unchanged.
3. The public API does not change (`apiCheck`).

Verification:
- `sdk/gradlew -p sdk :sdk-android:testDebugUnitTest --no-daemon`; `sdk/gradlew -p sdk :sdk-android:apiCheck --no-daemon`; `scripts/check-repo-rules.sh`.
