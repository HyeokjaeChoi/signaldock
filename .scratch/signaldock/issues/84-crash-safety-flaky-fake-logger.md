# 84. Fix the intermittent `CrashSafetyTest` closed-database failure

Status: resolved
Type: task
Blocked by: None
Execution: Done (PR #203, 2026-10-24 UTC)

## Scope and completion criteria

CI run 40080374490 (job `sdk-test`, 2026-10-24 UTC, docs-only PR #201) failed `CrashSafetyTest` `closing the database during many concurrent calls throws nothing` with `java.lang.ArrayIndexOutOfBoundsException at ArrayList.java:484`. Other runs of the same code passed.

Cause: in JDK 21, `ArrayList.java:484` is `elementData[s] = e` in `ArrayList.add`. This failure occurs when two threads add to one `ArrayList` at the same time. After the test closes the database, every `track()` and `diagnostics()` call on the 8 workers logs a WARN (ADR-0017 decisions 1 and 4). The test logger `FakeLogger` (`TestSupport.kt`) stored the WARNs in a `mutableListOf`. A concurrent `add` threw inside the SDK's catch block, and the exception reached the caller. Each `ArrayList` in SDK main code is local to one call, and `AndroidLogger` has no state. Thus the defect is in the test, not in the SDK.

Evidence (local, 200 iterations of the test body in one JVM, 2,400 WARNs expected each time): before the fix, the WARN count was 2,392 to 2,399 (lost adds); after the fix, it was always 2,400.

1. `FakeLogger.warnings` is a `CopyOnWriteArrayList`. The list type that tests read does not change.

Verification:
- The test body passes 200 iterations in a row; `CrashSafetyTest` passes; `:sdk-android:testDebugUnitTest`; `scripts/check-repo-rules.sh`; `scripts/verify-contract.sh`; local `/code-review`.
