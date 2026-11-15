---
status: accepted
---

# Production quality gates

The repository had fewer automated gates than its docs implied. The plan and the gate table are in `.scratch/quality-gates/spec.md`. This ADR records the decisions that the plan needs. Each implementation PR adds a dated amendment with its measured values.

## Context

On main 3b640b9 (2026-11-15):
- No detekt, ktlint, or Android Lint for `sdk/` and `server/`. `.github/workflows/claude-review.yml` told the reviewer that detekt and shellcheck already run; neither runs.
- No ESLint, Prettier, or bundle size budget for `dashboard/`, `perf/report/`, `web-shared/`.
- No k6 `thresholds`; Python decides pass or fail after the run ([ADR-0013](0013-server-load-measurement-harness.md), [ADR-0048](0048-demo-vm-capacity-measurement.md)).
- E2E delivery cases, device and Simulator durability, and API 24 and 37 runs are local only ([ADR-0011](0011-sdk-device-test-harness.md), [ADR-0029](0029-device-fault-tests-and-power-loss.md), ADR-0047 ticket 15). Some results are only in PR descriptions.
- No shellcheck, no coverage gate (rejected in [ADR-0047](0047-ios-sdk.md) D13), no main-thread or power-loss gate (`docs/engineering/failure-modes.md`, "Still open" 1 and 8).
- No scheduled CI tier except CodeQL.

## Decision

1. Three tiers. PR gates block merge; each new PR job takes 10 minutes or less on Linux, and no new macOS job runs on PR. Nightly gates (`.github/workflows/nightly.yml`, `schedule` and `workflow_dispatch`) run emulator, Simulator, Compose, and load jobs. Release workflows run only when the last nightly run on the tagged commit is `success`.
2. Thresholds are stated before the first run. When a threshold needs a measured base (bundle budget, load baseline, coverage, main-thread cost), the first PR commits the measured base and the rule (for example "base + 10%"), not a number picked without a run.
3. Static analysis has a committed baseline per module that may only shrink. A check in `scripts/check-repo-rules.sh` fails when a baseline gains an entry.
4. Coverage ratchet for `sdk/sdk-android` and `sdk-ios`: the job fails when line coverage drops more than 0.5 point below the committed value. The committed value may only rise: a `scripts/check-repo-rules.sh` check fails when a PR lowers it vs `origin/main`, as for baselines in decision 3, unless the same diff sets `lowered: {from: <origin/main value>, reason: <text>}` next to the value in the committed coverage file (for example after a toolchain or runner-image change, or removal of a tested API). The check reads the file, not commit messages, so it works in pre-commit and in the depth-1 CI checkout, and it prints the reason for the review. The job also fails when measured coverage is more than 0.5 point above the committed value, and prints the value to commit, so a rise is locked in by the same PR. This reverses the coverage rejection in ADR-0047 D13. Reason: ADR-0047 rejected a fixed floor because most surveyed vendors do not use one; a ratchet picks no number and only stops regressions in the two modules that hold event data on devices.
5. No automatic retry, as in ADR-0047 D13. A flaky gate is fixed at the cause. After two fixes that do not make it stable, the gate is removed and an amendment here records the removal and the reason.
6. Power loss (amends [ADR-0029](0029-device-fault-tests-and-power-loss.md), which says no emulator can cut power): SIGKILL of the emulator process loses the guest page cache, which is the part of a power cut that matters for the queue. The test makes a known sync point: the phase writes events, stops delivery (no network, WorkManager work cancelled), runs `PRAGMA wal_checkpoint(FULL)` from test code (a checkpoint syncs in `synchronous=NORMAL`) and asserts the result row `busy = 0` and `log = checkpointed`, writes more events, then the emulator is killed. The gate checks what ADR-0029 items 4 and 5 promise: the queue opens and `PRAGMA integrity_check` is `ok`; the server stores each `eventId` once and answers a resend with `duplicate` (not `conflict`); and no event committed before the checkpoint is lost. A busy checkpoint is a harness error that makes the run invalid, not a product failure. Loss of events after the checkpoint and resends (a lost queue-delete after an ACK) are counted, not gated. ADR-0029 "Limits" (CI does not run the fault cases) changes with gate A5. `synchronous=NORMAL` does not change.
7. k6 thresholds go in `perf/load.js` and `perf/overload.js` only, and only absolute ones: the error and status rates and `dropped_iterations`. The p95 rules of ADR-0013 are relative to a baseline of the same run, so they stay in Python, and the nightly SLO (B3) compares p95 with a committed baseline. The runners treat exit 99 as "threshold crossed" and still summarize, for the server-side thresholds. A crossed `dropped_iterations` threshold keeps the run `invalid`, as the runners already rule (a generator limit). Other non-zero exits make a run invalid. `perf/demo-vm/collect.js` has none: the [ADR-0048](0048-demo-vm-capacity-measurement.md) verdict needs `docker stats` and the first stage, so it stays in Python.
8. The release check of decision 1 runs in the `build` job of each release workflow with `actions: read`. `publish` keeps its permissions and runs no project code ([ADR-0039](0039-release-provenance-attestation.md)).
9. Dependency and repository gates. OSV-Scanner on PR and nightly reads the npm lockfiles and the `gradle/verification-metadata.xml` files, so it scans the resolved Gradle tree that ADR-0031 item 9 does not see. An ignored finding needs a reason and an expiry of at most 30 days. `renovate-config-validator --strict` runs on PR and closes the ADR-0031 "Gaps" item. The `main` ruleset is a committed file that the owner applies; a nightly job fails when the live ruleset differs from it. Dependabot security updates stay off (ADR-0031 item 8).
10. Numbers that go into the README, the portfolio, or a résumé come only from a merged PR: a CI run ID or a committed result file.

## Alternatives rejected

- GitHub dependency review on PR: the dependency graph does not parse the Gradle catalog (ADR-0031), so it covers npm and actions only. OSV-Scanner (decision 9) covers all of them.

- Battery target in CI: an emulator has no battery model. The proxy, how often WorkManager wakes the app, is fixed by design (ADR-0011).
- Ingest load SLO on PR: the noise of a shared runner is larger than the regressions it would catch. The nightly gate uses a 2x band against a baseline from the same runner class.
- A fixed coverage floor: see decision 4.
- Retry on flaky device or Lighthouse runs: see decision 5.

## Consequences

- About 90 Linux and 20 macOS runner minutes per night.
- A release can wait up to one night for a green nightly run, or for a `workflow_dispatch` run on the tagged commit.
- `claude-review.yml` names only tools that run.

## Amendment (2026-11-15, ticket 05, nightly workflow and E2E gate)

`.github/workflows/nightly.yml` exists, with `schedule` (`0 18 * * *`) and `workflow_dispatch`. Job `e2e-delivery` (gate Q1) runs `scripts/e2e-delivery.sh 1 2 3 4 5` against the Compose stack and an API 34 `google_apis` x86_64 emulator on `ubuntu-latest`, with the KVM and AVD cache setup of `device-test` (same cache key). Each nightly job writes a JSON summary (gate Q2; `scripts/nightly-summary.py`, format in `docs/engineering/nightly-summary.md`) and uploads it as the artifact `nightly-summary-<job>`. `scripts/check-repo-rules.sh` fails when `nightly.yml` has a trigger other than `schedule` and `workflow_dispatch`, or a job without the summary step. Tickets 06, 07, 08, and 11 add their jobs below the marker comment in the file.

Proof, by a temporary `push` trigger on the branch of the PR (removed before merge; the check above rejects it):

| Run | Commit | Result | Job time | Compose step | `e2e-delivery.sh` step |
|---|---|---|---|---|---|
| [43683069360](https://github.com/HyeokjaeChoi/signaldock/actions/runs/43683069360) | `6a5fbb9` (before the rebase on main) | success, cases 1-5 pass | 12 min 10 s | 1 min 46 s | 9 min 49 s |
| [43691646307](https://github.com/HyeokjaeChoi/signaldock/actions/runs/43691646307) | `aa553f8` (after the rebase and the JDK glob fix) | success, cases 1-5 pass | 11 min 25 s | 1 min 44 s | 8 min 56 s |

The AVD cache hit in both runs. The spec estimate for Q1 was about 20 minutes; the measured job time is about 12 minutes, so the 60-minute `timeout-minutes` has a wide margin. The times are measurements of two runs, not a threshold. A failing case was not forced in CI: the job fails through the non-zero exit of `scripts/e2e-delivery.sh` (`e2e_cases.py` exits 1 when a case fails), and `scripts/test_nightly_summary.py` checks that a failed case or job gives `result: fail`.
