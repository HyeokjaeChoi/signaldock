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

## Amendment (2026-11-15, ticket 02, web gates W1 to W4)

Spec gates W1 to W4 run in `scripts/verify-contract.sh` step 4 (CI job `verify-contract`). W5 (Lighthouse) is not part of this ticket.

**Decision: the lint package is the repository root `package.json`.** `typescript-eslint` 8.72.2 supports TypeScript below 6.1.0 only. `dashboard/` and `perf/report/` compile with TypeScript 7.0.2, which has no JS compiler API, so the type-aware rules cannot run on it, and npm cannot put two TypeScript versions behind one `typescript` name in the same package. The root package holds only lint and format tools, with `typescript` 6.0.3 for them; the apps keep 7.0.2 for `tsc`. The two app lockfiles stay separate (ADR-0008, ADR-0013). `eslint` is 9.39.5, not 10: `eslint-plugin-jsx-a11y` 6.10.2 allows up to 9. Renovate keeps the root `typescript` below 6.1.0 (`.github/renovate.json5`). Lift both limits when the plugins support them.

**Rules.**
- ESLint (`eslint.config.mjs`): `typescript-eslint` strict-type-checked and stylistic-type-checked; `react-hooks` (`.ts` and `.tsx`, the hooks live in both); `jsx-a11y` strict; `eslint-comments` (a disable comment has a description and suppresses something). `--max-warnings 0`. Tests may use `!`, async mocks without `await`, unbound `vi.fn` methods, and empty stubs. Numbers in template literals are allowed. The existing findings were fixed in code. One `eslint-disable` exists, with a reason: `dashboard/src/polling.ts`, a disabled query clears its state in an effect.
- Prettier `--check`, `printWidth` 140, over the same three directories (38 files reformatted once).
- `noUncheckedIndexedAccess` stays off; the findings that it would have removed were fixed one by one in `perf/report/src/App.tsx` (types now say that keys may be missing).

**axe-core** (`web-shared/a11y/axe.ts`, `axe-core` 4.14.0 in both apps): fails on any `serious` or `critical` violation of the whole document. `color-contrast` is off because jsdom has no layout; `web-shared/theme/contrast.test.ts` checks the tokens. Charts draw nothing in jsdom (stubbed in the Dashboard tests, as in `App.test.tsx`), so chart SVG is not covered. Result: 0 serious and 0 critical violations in 20 Dashboard tests (10 views in `en` and `ko`: list, details, empty, failed queries, failed details, stopped polling, details not found, custom period without dates, invalid id filter, narrow layout with the open disclosure) and 4 report tests (with and without the committed `perf/results/*index.json`, `en` and `ko`). Dashboard tests: 206 (186 before); report tests: 4 (none before). A deliberate violation (a `select` without a label) failed the suite when tried by hand.

**Bundle budget** (`scripts/check-bundle-budget.mjs`, no dependency, `bundle-budget.json` in each app): gzip level 9 of all `.js` files of a `vite build` into a temporary directory; budget = measured size + 10%, rounded up. `perf/report/` is built without `copy-data.mjs`, because that step writes the result data as `dist/index.js`, which grows with every measurement run and is not code. The budget changes only in a PR that says why (script header).

| App | Measured gzip JS (CI) | Budget | Evidence |
|---|---|---|---|
| `dashboard/` | 184,136 bytes | 202,550 bytes | CI run 43681844059, job `verify-contract` (step 4) |
| `perf/report/` | 197,352 bytes | 217,088 bytes | same run |

The local build gave the same sizes (184,135 before a one-byte code change). The CI run above also ran ESLint, Prettier, both type checks, and all tests with no failure.

**Not done here.** No check yet fails a PR that raises `budgetGzipBytes` (ADR-0049 decision 3 style, against `origin/main`); the ticket that owns `scripts/check-repo-rules.sh` can add it. The root lockfile has one moderate advisory (`minimatch` below `typescript-estree`, a dev tool that runs on repository files only); the fix needs a breaking `typescript-eslint` downgrade.

## Amendment (2026-11-15, ticket 03: shellcheck and actionlint, gates D1 and D2)

- Pins: shellcheck 0.11.0 and actionlint 1.7.12, each with a SHA-256 per platform (darwin and linux, arm64 and x64) in `scripts/setup-toolchain.sh`. `scripts/setup-toolchain.sh lint` installs only these two, so the CI `check` job downloads no JDK. `scripts/check-shell-lint.sh` runs only the pinned copies in `.tools/`, never a copy on PATH.
- Scope: shellcheck `-x --severity=warning` on the 27 tracked files that match `*.sh` or `.githooks/*`. The four `gradlew` wrappers are generated and are not covered. actionlint runs on all 7 workflows and calls the pinned shellcheck for `run:` blocks (default severity, pyflakes off).
- Runs in the `check` job of `repo-rules.yml` and in `.githooks/pre-commit`. A missing or wrong-version tool fails the run with a setup message.
- First run on main 3b640b9 (before fixes): shellcheck 9 findings in 5 scripts; actionlint 9 shellcheck messages in 5 `run:` steps. All fixed; no finding is baselined.

| File | Findings fixed |
|---|---|
| `scripts/check-size-budget.sh` | SC2155, SC2010: `export JAVA_HOME=$(ls \| grep)` became a glob |
| `scripts/update-gradle-verification.sh` | SC2155, SC2010: same, glob `jdk-21*` |
| `scripts/device-durability.sh` | SC2010: same |
| `scripts/device-measure.sh` | SC1090: `# shellcheck source=/dev/null` (operator-supplied env file); SC2155: `runId` assigned on its own line |
| `scripts/host-cost-measure.sh` | SC2034 twice: `PKG_sdk`, `PKG_nosdk` are read as `${!pkg_var}`; two `# shellcheck disable=SC2034` with that reason |
| `codeql.yml`, `release.yml` | SC2155, SC2010 each: same glob |
| `repo-rules.yml` (`sdk-test`, `server-test`, `device-test`) | SC2155, SC2010 (5 messages): same glob |

Behavior change: only when `.tools/` holds more than one `jdk-*` directory. The old code built a multi-line path and failed; the glob uses the first match. With no JDK, the error message shows the glob instead of an empty name; `device-durability.sh` now has the same `-x "$JAVA_HOME/bin/java"` check as the other scripts, so it still fails at once.

Evidence: CI run 43678422518 (PR 282), job `check` passed with both steps.

## Amendment (2026-11-15, ticket 04, k6 thresholds)

Gate B2, as decision 7 states it. `perf/load.js` and `perf/overload.js` now declare k6 `thresholds`, so k6 exits with status 99 when a run crosses one. Before, only Python decided pass or fail after the run ([ADR-0013](0013-server-load-measurement-harness.md)). `perf/demo-vm/collect.js` has none.

### Thresholds

| Script | Threshold | Crossed when |
|---|---|---|
| `load.js` | `http_req_failed{phase:measure}` | 1% or more of the requests in the measure phase fail. Requests now carry the `phase` tag, so warm-up and the tail iteration do not count |
| `load.js`, `overload.js` | `dropped_iterations` | any iteration is dropped (the VU cap was reached) |
| `overload.js` | `unexpected_status` | the rate of answers with a status other than 200 and 503 is above 0. Status 0 (timeout, network error) counts. This is the ADR-0013 rule "only 200 and 503 answers" |

There is no `http_req_failed` threshold in `overload.js`: the run must shed with 503, and k6 counts 503 as failed. There is no p95 threshold: the p95 rules of ADR-0013 are relative to a baseline of the same run (`perf/overload.py`), so they stay in Python, and so does every other pass rule of ADR-0013 and ADR-0048.

### Decisions

1. `perf/common.py` `k6_exit_reasons` turns the k6 exit code into run reasons for `run_one.py` and `overload.py`. Exit 0: none. Exit 99: the run is complete, so it is summarized. A crossed server threshold is a `fail` reason that names it. A crossed `dropped_iterations` is an `invalid` reason: a generator limit, not server behavior, as the runners already rule. Both can apply, and `invalid` ranks first. The existing `dropped` check of each runner stays as a second guard, so one crossed `dropped_iterations` can give two `invalid` reasons. Exit 99 with no crossed threshold in the summary is `invalid` too (the harness cannot read k6, which says nothing about the server). Any other exit code stays `invalid` ("phases may be incomplete"). Before, any non-zero exit made the run `invalid`.
2. No `abortOnFail`: the summaries need the whole run.
3. The thresholds are absolute, so they can be weaker than the Python rules. `load.js` allows up to 1% failed requests in the measure phase; `run_one.py` still fails the run on any non-ok answer, and `overload.py` fails it on any status other than 200 and 503. So k6 exit 0 does not mean verdict `pass`, and a `fail` can carry both a k6 reason and a Python reason.
4. A threshold on a metric without samples passes, and a Trend cannot use `count`. So every threshold has a proof that it can fail (below). `k6 --summary-export` writes `true` for a crossed threshold.

### Proof

Local, no CI run. Tools: k6 v2.3.0, Python 3.9.6, macOS.

1. Mock server, [`perf/threshold-proof/results.md`](../../perf/threshold-proof/results.md), written by `perf/threshold-proof/proof.py` (9 of 9 cases matched; each case compares the crossed set and the run verdict with the expected ones, exactly). Each threshold fails on its own fault, the clean cases cross nothing, and the Python parsers (`run_one.read_k6`, `overload.read_raw`) read the output also after exit 99:

| Script | Fault | k6 exit | Crossed | Run verdict |
|---|---|---|---|---|
| `load.js` | none | 0 | none | pass |
| `load.js` | every collect answers 500 in the first 4.5 s (warm-up) | 0 | none | pass |
| `load.js` | every 20th collect answers 500 | 99 | `http_req_failed{phase:measure}` | fail |
| `load.js` | collect held 1.5 s at 40 req/s | 99 | `dropped_iterations` | invalid |
| `load.js` | both faults | 99 | both | invalid, with a fail reason |
| `overload.js` | 503 on every 2nd collect and by-name in the overload phase | 0 | none | pass |
| `overload.js` | every 50th collect answers 500 | 99 | `unexpected_status` | fail |
| `overload.js` | every 100th collect answers after 6 s (timeout, status 0) | 99 | `unexpected_status` | fail |
| `overload.js` | collect held 2.5 s at 200 req/s | 99 | `dropped_iterations` | invalid |

The observed values (they vary from run to run) are in `results.md`.

2. Real run scripts, [`perf/threshold-proof/runner-runs.md`](../../perf/threshold-proof/runner-runs.md): `run_one.py` and `overload.py` on the Docker stack, clean, with one threshold made impossible to meet, and with the generator starved of VUs. With a crossed server threshold both write every result file with verdict `fail`; with `dropped_iterations` crossed both give `invalid` and no `fail` reason from it; the clean `run_one.py` run is `pass`.

3. Replay on committed results, no new run (the last table of `results.md`, also written by `proof.py`): `pilot-1` and `pilot-2` have 0 of 2,400 measure-phase requests not ok and no dropped iteration; `overload-1` has only the statuses 200 and 503 and no dropped iteration. None crosses a threshold.

### Not covered

- The clean `overload.py` run on the Docker stack had k6 exit 0 (`unexpected_status` stayed at 0 over 10,881 answers, with real 503 answers) but the verdict `fail` from the Python rules of ADR-0013 (recovery p95 509 ms against a limit of 277 ms, and non-200 answers in the last 30 s of recovery). The Mac was shared with other running sessions. This PR does not claim a clean pass of the whole overload run; it claims that the thresholds do not fail it.
- No load run in CI. B3 (nightly ingest load SLO) compares p95 with a committed baseline.

## Amendment (2026-11-15, ticket 05, nightly workflow and E2E gate)

`.github/workflows/nightly.yml` exists, with `schedule` (`0 18 * * *`) and `workflow_dispatch`. Job `e2e-delivery` (gate Q1) runs `scripts/e2e-delivery.sh 1 2 3 4 5` against the Compose stack and an API 34 `google_apis` x86_64 emulator on `ubuntu-latest`, with the KVM and AVD cache setup of `device-test` (same cache key). Each nightly job writes a JSON summary (gate Q2; `scripts/nightly-summary.py`, format in `docs/engineering/nightly-summary.md`) and uploads it as the artifact `nightly-summary-<job>`. `scripts/check-repo-rules.sh` fails when `nightly.yml` has a trigger other than `schedule` and `workflow_dispatch`, or a job without the summary step. Tickets 06, 07, 08, and 11 add their jobs below the marker comment in the file.

Proof, by a temporary `push` trigger on the branch of the PR (removed before merge; the check above rejects it):

| Run | Commit | Result | Job time | Compose step | `e2e-delivery.sh` step |
|---|---|---|---|---|---|
| [43683069360](https://github.com/HyeokjaeChoi/signaldock/actions/runs/43683069360) | `6a5fbb9` (before the rebase on main) | success, cases 1-5 pass | 12 min 10 s | 1 min 46 s | 9 min 49 s |
| [43691646307](https://github.com/HyeokjaeChoi/signaldock/actions/runs/43691646307) | `aa553f8` (after the rebase and the JDK glob fix) | success, cases 1-5 pass | 11 min 25 s | 1 min 44 s | 8 min 56 s |

The AVD cache hit in both runs. The spec estimate for Q1 was about 20 minutes; the measured job time is about 12 minutes, so the 60-minute `timeout-minutes` has a wide margin. The times are measurements of two runs, not a threshold. A failing case was not forced in CI: the job fails through the non-zero exit of `scripts/e2e-delivery.sh` (`e2e_cases.py` exits 1 when a case fails), and `scripts/test_nightly_summary.py` checks that a failed case or job gives `result: fail`.

## Amendment (2026-11-15, ticket 06: Android device gates)

Gates A3, A4, A5, and A6 are built. Evidence: the `device-test` run of this PR for A3 (run ID in the PR), and three runs of a temporary workflow on this branch (`gate06-proof.yml`, removed in the same PR) that ran the nightly steps on the nightly runner class: `ubuntu-latest`, KVM, x86_64 `google_apis` emulators at API 24 and API 37.0 (the newest stable x86_64 image; `37.2` exists only as `ps16k`). The runs are 43692865454 (first full run and the negative control), 43694083496 (second try of two cases), and 43695424448 (final full run). The job is `device-durability` in `nightly.yml` (matrix API 24 and 37.0). The `device-test` run of the PR is 43743427552 (A3 passed). Dispatch runs of the nightly job are listed below.

- **A3.** `MainThreadStrictMode` (a JUnit rule: `detectAll`, `penaltyLog`, `penaltyDeath` on the main thread) wraps `StrictModeDeviceTest`, which calls `initialize`, `track` (25 events, so the foreground trigger and the real WorkManager scheduler run), `diagnostics`, the foreground drain, `setOptOut`, `isOptedOut`, and `clearQueue` on the main thread. The SDK has no public `flush()`; the foreground drain stands in for it. No violation was found, so no SDK change was needed. Proof that the rule can fail: a deliberate `File.exists()` on the main thread killed the process with `StrictModeDiskReadViolation` and failed the run (local API 24 emulator). The `device-test` job is unchanged.
- **A6.** Phases `powerloss_prepare`, `powerloss_write`, `powerloss_check` (`PowerLossPhases.kt`) and case `powerloss` of `scripts/device-durability.sh`. Two choices differ from decision 6: delivery is stopped by construction (the SDK is built with no scheduler and no foreground trigger, WorkManager work is also cancelled; airplane mode is not used, because the check needs the network again after the boot), and a set of events whose ACK the host server drops (`/__mode/drop` of `scripts/ack-server.py`) is queued before the checkpoint, so that the resend after the boot is certain and the answer `duplicate` (not `conflict`) is observed, not only expected. The host server mirrors the architecture table ("Lost ACKs and event deduplication"): same content is `duplicate`, other content is `rejected` with `CONFLICT`. It does not replace the server's own conflict tests (E2E case 4). `scripts/powerloss-verify.py` judges the server log; `scripts/test_powerloss_verify.py` tests it. The queue is checked before the SDK opens it, with `PRAGMA integrity_check` on a connection that has no `DatabaseErrorHandler` (the default one deletes a corrupt database). Results of run 43695424448 (each: integrity `ok`, checkpoint `busy=0 log=36 checkpointed=36`, 30 lost-ACK and 30 pending events still queued, server answers `duplicate`, never `conflict`): API 24: 0 of 20 events written after the checkpoint lost, 60 `duplicate` answers (counted answers, 30 events: the batch reached the server twice before the boot, probably an OkHttp retry after the dropped answer, not checked); API 37.0: 20 of 20 lost, 60 `duplicate` answers. Negative control (`POWERLOSS_SKIP_CHECKPOINT=1`, no checkpoint, API 37.0, run 43692865454): 11 of 30 pending events lost, the case failed. So the gate can fail and the emulator kill does lose unsynced WAL data when the platform uses `synchronous=NORMAL`.
- **API 24 uses `synchronous=FULL`.** On the API 24 emulator the SDK connection reports `PRAGMA synchronous` = 2, so every commit syncs and a kill loses nothing (the 0 of 20 above, and no loss even without the checkpoint, locally). `PowerLossSemanticsDeviceTest` fails there (it asserts 1); CI `device-test` runs API 34, where it holds. The gate is meaningful on API 37 (NORMAL); on API 24 it checks integrity and the resend path only. Follow-up: `.scratch/quality-gates/issues/13-api24-synchronous-full.md`.
- **A5.** `scripts/device-durability.sh` runs every case in its own subshell (a failing case does not stop the others), writes `[{name, result, seconds}]` to `DURABILITY_RESULT_JSON` and `key=value` lines to `DURABILITY_NUMBERS`, and exits 1 if any case did not pass. Cases: the 11 of ADR-0011 and ADR-0029, `powerloss`, and `maincost`. Run 43695424448: 13 of 13 passed at API 24 and 13 of 13 at API 37.0. Two harness changes: `airplane` uses `svc data` and `svc wifi` before API 30 (the `cmd connectivity airplane-mode` command does not exist there; the first run timed out on API 24), and `restore` prints the `bmgr` output when the backup does not succeed. `restore` failed at both APIs in run 43692865454 ("backup did not succeed", output not recorded), then passed in four later runs (two alone, two full); the cause is not known, and the new output shows it if it recurs.
- **A4.** `MeasurePhases.main_thread` times 1,000 `track()` calls made from a coroutine on the main thread (endpoint unreachable, as `offline_store`); the wall time of a call bounds from above how long the call keeps the main thread. Case `maincost` gates p99. Measured p99 on the CI emulators: API 24: 10,033, 6,860, 10,079, 7,821, 7,119, 7,963, 9,428 microseconds; API 37.0: 55,155, 5,790, 14,344, 4,143, 5,002, 4,351, 33,751 microseconds (p50: 2.5 to 2.7 ms and 0.7 to 5.0 ms). The spread is one order of magnitude on a shared runner, so the rule is: budget = 2 x the worst measured p99, rounded = 110,000 microseconds, `MAIN_THREAD_P99_BUDGET_US` in the script. It catches a slowdown of about 2 x the worst run, such as disk I/O on the main thread per call; it does not claim to measure ANR risk (the ANR limit is 5 s). Change it only with a new measured base and an amendment.
- **Counts.** `device-test` now starts 38 methods: 20 skip themselves without `-e phase`, 18 run (the new ones: `StrictModeDeviceTest` runs; `PowerLossPhases` has 3 and `MeasurePhases.main_thread` 1 that skip). ADR-0023 and ADR-0029 carry matching amendments.

