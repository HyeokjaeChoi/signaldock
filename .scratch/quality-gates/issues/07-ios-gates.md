# 07. iOS gates: Main Thread Checker (I1) and Simulator durability nightly (I2)

Status: resolved
Type: task
Execution: Done (PR #294). Runs: `ios-durability` 43752097047 (nightly form, 7 of 7, 694 s); temporary runs 43691633861, 43695114331 (with the `mtc-probe` proof). Follow-up: `07-followup-flaky-background-timer-test.md`.

## Scope and completion criteria

Spec gates I1, I2. ADR-0049 decisions 1, 2, 5. ADR-0047 D13, D15, ticket 15 amendment.

1. I1 (PR tier, `ios-test` and `ios-sanitize`, no new job): the Simulator tests of `scripts/verify-ios.sh` fail when the Main Thread Checker reports. `xcodebuild` has no switch for the checker (`xcodebuild -help` of Xcode 27.2 lists `-enableThreadSanitizer`, `-enableAddressSanitizer`, `-enableUndefinedBehaviorSanitizer`, no `-enableMainThreadChecker`). The scheme that SwiftPM generates has it on for tests, and a report is only a log line: the test run stays green. So the script reads the log. Prove it with a sample test that calls a UIKit setter off the main thread, then remove the sample.
2. I2 (nightly, `macos-26`): `scripts/simulator-durability.sh` runs all cases (`kill_store kill_send restart update reinstall optout offline`) with the stack it needs. The GitHub macOS runner cannot run containers (no nested virtualization), so a new script `scripts/simulator-durability-ci.sh` starts PostgreSQL (Homebrew) and the server (Gradle `installDist`, run on the host), boots the Simulator with a deadline, and runs the cases. Fails on any lost or unreported event. No retry.
3. The nightly job goes into `.github/workflows/nightly.yml` after ticket 05 merges (session gate-05-nightly). Until then a temporary workflow on the branch proves the job; it is removed before merge. `AGENTS.md` and `docs/design-index.md` change only after ticket 05 merges. Do not touch `scripts/verify-contract.sh` (ticket 09).
4. JSON summary: the per-case file that `scripts/nightly-summary.py` reads (`[{"name", "result", "seconds"}]`, from ticket 05), written by `scripts/simulator-durability.sh` when `SIMULATOR_DURABILITY_RESULT_JSON` names a file.
5. ADR-0049 dated amendment: run IDs, cases passed, macOS job time (budget about 20 minutes per night), the proof of I1.

## Comments

- 2026-11-15: claimed. Findings: the checker is on by default in the generated scheme and does not fail the run (probe, Xcode 27.2, iOS 27.0 Simulator: `Main Thread Checker: UI API called on a background thread: -[UILabel setText:]` in the log, `** TEST SUCCEEDED **`). `macos-26` image 20261102.0473.1 lists no Docker and no PostgreSQL; it has Homebrew, JDK 21, Gradle, Xcode 26.6, iOS 26.2, 26.4, 26.5 runtimes, 3 vCPU and 7 GB RAM (GitHub docs).
