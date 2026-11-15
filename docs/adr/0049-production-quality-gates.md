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
4. Coverage ratchet for `sdk/sdk-android` and `sdk-ios`: the job fails when line coverage drops more than 0.5 point below the committed value. The committed value may only rise: a `scripts/check-repo-rules.sh` check fails when a PR lowers it vs `origin/main`, as for baselines in decision 3, unless a commit in the PR has the trailer `Coverage-Lowered: <reason>` (for example a toolchain or runner-image change, or removal of a tested API); the check prints the reason so the review sees it. The job also fails when measured coverage is more than 0.5 point above the committed value, and prints the value to commit, so a rise is locked in by the same PR. This reverses the coverage rejection in ADR-0047 D13. Reason: ADR-0047 rejected a fixed floor because most surveyed vendors do not use one; a ratchet picks no number and only stops regressions in the two modules that hold event data on devices.
5. No automatic retry, as in ADR-0047 D13. A flaky gate is fixed at the cause. After two fixes that do not make it stable, the gate is removed and an amendment here records the removal and the reason.
6. Power loss (amends [ADR-0029](0029-device-fault-tests-and-power-loss.md), which says no emulator can cut power): SIGKILL of the emulator process loses the guest page cache, which is the part of a power cut that matters for the queue. The test makes a known sync point: the phase writes events, stops delivery (no network, WorkManager work cancelled), runs `PRAGMA wal_checkpoint(FULL)` from test code (a checkpoint syncs in `synchronous=NORMAL`) and asserts the result row `busy = 0` and `log = checkpointed`, writes more events, then the emulator is killed. The gate checks what ADR-0029 items 4 and 5 promise: the queue opens and `PRAGMA integrity_check` is `ok`; the server stores each `eventId` once and answers a resend with `duplicate` (not `conflict`); and no event committed before the checkpoint is lost. A busy checkpoint is a harness error that makes the run invalid, not a product failure. Loss of events after the checkpoint and resends (a lost queue-delete after an ACK) are counted, not gated. ADR-0029 "Limits" (CI does not run the fault cases) changes with gate A5. `synchronous=NORMAL` does not change.
7. k6 thresholds go in `perf/load.js` and `perf/overload.js` only. The runners treat exit 99 as "threshold crossed" and still summarize, for the server-side thresholds. A crossed `dropped_iterations` threshold keeps the run `invalid`, as the runners already rule (a generator limit). Other non-zero exits make a run invalid. `perf/demo-vm/collect.js` has none: the [ADR-0048](0048-demo-vm-capacity-measurement.md) verdict needs `docker stats` and the first stage, so it stays in Python.
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
