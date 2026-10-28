# 31. iOS CI: parallel tests starve the thread pools

Status: ready-for-agent
Type: task
Blocked by: None

## Scope and completion criteria

On the 3-core macOS CI runner, the 268 parallel iOS tests starve libdispatch and the Swift cooperative pool for 27 to 55 s. The CI probe in PR #258 measured: a GCD timer woke 33 s late, `Task.sleep` woke 27 s late, and a plain `Thread` ran on time. The test that saturates the pools is not known. PR #258 works around it with a 600 s transport-test timeout.

1. Find the test or tests that saturate the pools (for example a blocking call on the cooperative pool, or `DispatchSemaphore` or another synchronous wait in a test or in SDK code).
2. Fix the cause.
3. Remove the 600 s transport-test workaround if it is no longer needed.

Verification:
- `scripts/verify-ios.sh tsan` passes on the CI runner without the workaround, in the usual time. The PR names the cause and the CI run.
- `bash scripts/check-repo-rules.sh; echo "exit=$?"` is 0.
