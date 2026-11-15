# 07 follow-up. `DeliveryIntegrationTests.aLaunchInTheBackgroundArmsNoRetryTimerUntilTheAppReturns` failed once

Status: needs-triage
Type: task
Severity: Minor

Found while pushing ticket 07 (2026-11-15). The pre-push `verify-contract.sh` iOS step (TSan, `scripts/verify-ios.sh tsan`, iOS 27.0 Simulator, Xcode 27.2) ended with `** TEST FAILED **` and named this test. A second run of `scripts/verify-ios.sh tsan` on the same commit and Simulator passed (293 tests), and so did three earlier runs of the same suite. The ticket 07 change (Main Thread Checker log check, `-parallel-testing-enabled NO`) does not change test code or timing; the failing assertion text was not kept.

Suspect: the test posts `willEnterForegroundNotification` right after `Harness.make(...)` and `waitFinished(1)`; if the observer is registered after the first drain finishes, or the main-actor hop runs late under load, the second `waitFinished(2)` can wait for a drain that never starts. Reproduce with `xcodebuild test ... -only-testing:SignalDockTests/DeliveryIntegrationTests/aLaunchInTheBackgroundArmsNoRetryTimerUntilTheAppReturns -test-iterations 50` under load. ADR-0049 decision 5 applies: no retry; fix at the cause.
