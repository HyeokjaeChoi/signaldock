# 86. Correct the instrumented test count in issues 73 and 85

Status: resolved
Type: task
Blocked by: None
Execution: Done (PR #205, 2026-10-24 UTC)

## Scope and completion criteria

Issue 73 wrote "Finished 49 tests ... 33 ran and passed". On 2026-10-24 UTC the same command on SDK 0.3.0 gave 33 test cases in the XML report: 17 passed, 0 failed, 16 skipped. Gradle still printed "Finished 49 tests" (33 plus the 16 skipped counted again).

1. Issue 73 gets a dated correction beside the wrong sentence. The rest of that record is unchanged.
2. Issue 85 gets a "Test counts" note with the XML counts and the likely cause of 49.
3. No other doc states these counts (`git grep "49 tests"`).

Verification:
- `bash scripts/check-repo-rules.sh`; the XML recount in `sdk/sdk-android/build/outputs/androidTest-results/connected/debug/`.
