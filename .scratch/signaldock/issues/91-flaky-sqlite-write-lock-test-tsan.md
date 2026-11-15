# 91. Flaky test: `SQLiteDatabaseTests.aSecondConnectionWaitsForTheWriteLockInsteadOfFailing` under TSan

Status: needs-triage
Type: task

Seen once in CI job `ios-test` (`scripts/verify-ios.sh tsan`): run 43751287956, job 132925585116, on PR 290 (a change with no iOS source). The test failed after 24.649 s with `Expectation failed: await waiter.value` at `sdk-ios/Tests/SignalDockTests/SQLiteDatabaseTests.swift:58`. The suite took 27 s; the whole run took 68 s.

ADR-0049 decision 5: no retry inside a gate. Find the cause (a wait time that is too short under TSan on a shared runner, or a real lost wake-up of the write lock) and fix it there. If the same test fails again on an unrelated PR, fix it before more iOS merges.
