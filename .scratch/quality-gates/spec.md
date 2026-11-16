# Production quality gates: plan

Status: ready-for-agent
Decisions: ADR-0049
Requested by: session "세션 맥락 복원" (2026-11-15), for the portfolio and résumé.
Rule for every number in this file: a threshold is a target until a merged PR shows a run. The results table at the end lists only run IDs and committed file paths.

## Verified gaps (checked on main 3b640b9)

| # | Gap | Evidence |
|---|---|---|
| 1 | No detekt, ktlint, spotless, or Android Lint config or CI step for `sdk/` and `server/` | `git grep` finds none; `.github/workflows/claude-review.yml:47` says detekt and shellcheck "already check", which is false |
| 2 | No ESLint, Prettier, or bundle size budget for `dashboard`, `perf/report`, `web-shared` | `dashboard/package.json` scripts; `check-bundle-key` is a secret check |
| 3 | No k6 `thresholds`; no load run in CI | `perf/load.js`, `perf/overload.js`, `perf/demo-vm/collect.js`; verdicts come from Python after the run |
| 4 | E2E 5 cases, device durability, simulator durability, API 24 and 37 runs are local only | headers of `scripts/e2e-delivery.sh`, `scripts/device-durability.sh`, `scripts/simulator-durability.sh`; CI `device-test` runs API 34 in-process tests only |
| 5 | No shellcheck; no coverage gate (rejected, ADR-0047 D13); no ANR, StrictMode, or battery target; power loss not observed | `docs/engineering/failure-modes.md` "Still open" 1 and 8 |
| 6 | Dependency and repository management: Renovate runs (ADR-0031; PRs 277-280 open, Dependency Dashboard 150) and Dependabot alerts are on (0 open), but no PR gate checks vulnerabilities, the Renovate config is validated only locally (ADR-0031 "Gaps"), and `main` has no branch protection or ruleset, so "merge when all checks pass" (AGENTS.md rule 7) is not enforced by GitHub | live repo settings 2026-11-15: `vulnerability-alerts` 204, `automated-security-fixes` off (by design, ADR-0031 item 8), secret scanning and push protection on, `branches/main/protection` 404, only ruleset `demo-tags` |

No workflow has a `schedule:` trigger except `codeql.yml`, so there is no nightly tier now.

## Tiers

- PR: blocks merge. Budget: each new job 10 min or less on `ubuntu-latest`; no new macOS job on PR (macOS minutes cost 10x).
- Nightly (`nightly.yml`, `schedule` + `workflow_dispatch`): emulator, Simulator, Compose, and load jobs. A failure opens no PR block; it fails the run, and the owner triages it into `.scratch/`.
- Release (`release.yml`, `release-ios.yml`): the release job needs the last nightly run on the tagged commit to be green (`gh run list` check in the release workflow).

## Area reviews and gates

Staff Product Engineer scope: gates that stop a real regression in this product (lost or duplicate events, broken contract, slower ingest, inaccessible Dashboard). Not gates that only add a badge.

### Android SDK (Staff Engineer, Android)

| Gate | Stops | Fails when | Tier | Cost |
|---|---|---|---|---|
| A1 Android Lint | API misuse, missing `@RequiresApi`, manifest and WorkManager errors | any error; warnings are errors (`warningsAsErrors`), checked against a committed `lint-baseline.xml` that may only shrink | PR (`sdk-test`) | ~2 min |
| A2 detekt + ktlint rules (detekt `formatting` plugin, one tool) | complexity, swallowed exceptions, style drift | any finding not in the committed baseline; baseline may only shrink (repo-rules check) | PR | ~1 min |
| A3 StrictMode in instrumented tests | disk or network I/O on the main thread from `track()`, `flush()`, init | a `StrictMode` violation with `penaltyDeath` in a test rule around the public API | PR (`device-test`) | 0 (same job) |
| A4 Main-thread cost (ANR proxy) | slow `track()` on the main thread | p99 of `track()` on the main thread > budget from `MeasurePhases` (owner sets the number from a first run) | Nightly | ~5 min |
| A5 Durability on emulator: `device-durability.sh` all cases | event loss across kill, update, clear, reinstall, restore, airplane, full disk, WorkManager lock | any lost or unreported event | Nightly, API 24 and 37 matrix | ~25 min per API |
| A6 Power loss: SIGKILL of the emulator process, cold boot without snapshot | a corrupt queue, duplicates, or loss beyond what ADR-0029 accepts after the guest page cache is lost | the queue does not open or `PRAGMA integrity_check` is not `ok`; the server stores an `eventId` twice or answers a resend with `conflict` (a resend answered `duplicate` is correct: a lost queue-delete after an ACK brings events back, delivery is at-least-once); or an event committed before a test-forced `PRAGMA wal_checkpoint(FULL)` is lost (delivery is stopped first; a result with `busy` not 0 makes the run invalid) (ADR-0049 decision 6; ADR-0029 items 4 and 5; `synchronous=NORMAL` stays). Loss after the checkpoint and resend counts are recorded, not gated | Nightly | ~10 min |
| A7 Semver check of the API dump | a removed or changed public symbol without a version change | the dump has a removed line vs the last `v*` tag and `SDK_VERSION` major (minor before 1.0) did not change | PR (`verify-contract.sh`) | seconds |

Rejected: a battery target in CI. An emulator has no battery model; the proxy (wakeups per hour from WorkManager) is already fixed by design (ADR-0011). Record in the ADR.

### iOS SDK (Staff Engineer, iOS)

Existing gates are already strict (swift format, TSan, ASan, UBSan, analyze, API interface, size budget).

| Gate | Stops | Fails when | Tier | Cost |
|---|---|---|---|---|
| I1 Main Thread Checker on in Simulator tests | UIKit or main-actor misuse | any checker report (`-enableMainThreadChecker YES`, report fails the test) | PR (`ios-test`, no new job) | 0 |
| I2 `simulator-durability.sh` all cases | event loss across kill, update, reinstall, opt-out, offline | any lost or unreported event | Nightly (`macos-26`) | ~20 min |
| I3 Semver check of `SignalDock.swiftinterface` | as A7 | as A7, against the last `ios-v*` tag | PR | seconds |

### Backend: Ktor and PostgreSQL (Staff Engineer, Backend)

| Gate | Stops | Fails when | Tier | Cost |
|---|---|---|---|---|
| B1 detekt + ktlint rules | as A2 | as A2 | PR (`server-test`) | ~1 min |
| B2 k6 `thresholds` in `perf/load.js` and `perf/overload.js` (not `perf/demo-vm/collect.js`: ADR-0048 finds capacity with failing stages, and its `invalid` and relative-p95 rules need `docker stats` and another run, so its verdict stays in Python) | a run that a human reads as pass while it failed | `http_req_failed` rate > 1% (`load.js`); for `overload.js`, no `http_req_failed`, and any answer with a status not in {200, 503}, status 0 included (ADR-0013), `dropped_iterations` > 0, on measure-phase submetrics where the script tags phases. No p95 threshold: the ADR-0013 p95 rules are relative to a baseline of the same run and stay in Python. k6 exits 99; `run_one.py` and `overload.py` treat 99 as "threshold crossed" (summarize and record it) for the server-side thresholds; a crossed `dropped_iterations` threshold keeps the run `invalid` (a generator limit, as the runners already rule); other non-zero exits are invalid | all runs of those two scripts | 0 |
| B3 Ingest load SLO, Compose on the runner | ingest throughput or latency regression | at a fixed 20 req/s x 20 events for 120 s: any non-200, or p95 > 2x the committed baseline of the same runner class | Nightly | ~8 min |
| B4 Container image scan on PR | a new Critical or High CVE before the tag | Trivy finds Critical or High with a fix (same rule as `demo-deploy.yml`) | PR, only when `server/` or `dashboard/` Dockerfiles or deps change | ~3 min |

Rejected: a load SLO on PR (runner noise is larger than the effect; nightly with a 2x band is the honest form).

### Web: Dashboard, perf report, web-shared (Staff Engineer, Web)

| Gate | Stops | Fails when | Tier | Cost |
|---|---|---|---|---|
| W1 ESLint (`typescript-eslint` strict-type-checked, `react-hooks`, `jsx-a11y`) | hook-order bugs, unhandled promises, missing labels | any error; no `eslint-disable` without a reason comment (lint rule) | PR (`verify-contract` step 4) | ~1 min |
| W2 Prettier `--check` | format drift | any diff | PR | seconds |
| W3 Bundle budget (a script over `dist/` gzip sizes; no new dependency) | bundle growth | gzip JS > committed budget; budget = first measured size + 10% | PR | seconds |
| W4 axe-core in Vitest on each view (jsdom) | WCAG A and AA rule failures | any `serious` or `critical` violation | PR | ~1 min |
| W5 Lighthouse CI on `vite preview` with fixture API | LCP, CLS, TBT regression | LCP > 2.5 s, CLS > 0.1, TBT > 200 ms (Google "good" bands), median of 3 runs | Nightly | ~5 min |

### DevOps and CI (Staff Engineer, DevOps)

| Gate | Stops | Fails when | Tier | Cost |
|---|---|---|---|---|
| D1 shellcheck on every tracked `*.sh` and `.githooks/*` | quoting and `set -e` bugs | any finding at `--severity=warning`; version pinned in `setup-toolchain.sh` with SHA-256 | PR (`check`) | seconds |
| D2 actionlint on `.github/workflows` | broken expressions, wrong `needs` | any finding; pinned binary with SHA-256 | PR (`check`) | seconds |
| D3 Fix `claude-review.yml:47` to name only tools that run | a reviewer that skips a check nobody runs | n/a (text fix in the PR that adds detekt and shellcheck) | PR | 0 |
| D4 Release needs a green nightly on the tagged commit | a release that skipped the device and load tiers | the last `nightly.yml` run on `GITHUB_SHA` is not `success`, or there is none. The check runs in the `build` job of `release.yml` and `release-ios.yml`, which gets `actions: read`; `publish` stays without project code and its permissions do not change (ADR-0039, ADR-0047 Ticket 13). An API error fails the job with a message that names the permission | Release | seconds |

### QA and Reliability (Staff Engineer, QA)

| Gate | Stops | Fails when | Tier | Cost |
|---|---|---|---|---|
| Q1 `e2e-delivery.sh` cases 1-5 on an emulator + Compose | lost events on ACK loss, wrong partial rejection, content conflict, missing client report | any case fails | Nightly | ~20 min |
| Q2 Committed result files for nightly runs | numbers that live only in PR text | a nightly job writes a JSON summary as an artifact; a monthly PR commits one snapshot under `perf/results/` or `docs/engineering/verification/` | Nightly + manual | 0 |
| Q3 Coverage ratchet | coverage regressions in `sdk/sdk-android` and `sdk-ios` | line coverage more than 0.5 point below the committed value, or more than 0.5 point above it (the job prints the value to commit); a `scripts/check-repo-rules.sh` check fails when a PR lowers the committed value vs `origin/main`, unless the same diff sets `lowered: {from, reason}` in the committed coverage file | PR | ~1 min |

### Dependencies and repository management (Staff Engineer, DevOps; security view)

| Gate | Stops | Fails when | Tier | Cost |
|---|---|---|---|---|
| R1 `renovate-config-validator --strict`, exact `renovate` version | a Renovate config error that silently stops all update and security PRs | invalid config or a deprecated option | PR (`check`) | ~1 min |
| R2 OSV-Scanner, pinned binary with SHA-256, over `dashboard/package-lock.json`, `perf/report/package-lock.json`, and the four `*/gradle/verification-metadata.xml` (OSV-Scanner reads them as Gradle lockfiles, so the whole resolved tree is scanned, not only the catalog entries ADR-0031 item 9 covers) | merging a dependency with a known vulnerability | any finding that is not in `osv-scanner.toml`; every ignore entry has a reason and an `ignoreUntil` at most 30 days ahead (a `scripts/check-repo-rules.sh` check) | PR, and nightly on `main` (new advisories appear without a code change) | ~1 min |
| R3 Ruleset for `main` as code: `.github/rulesets/main.json` (pull request required, required status checks = the PR jobs of `repo-rules.yml` (with `verify-contract`), CodeQL `analyze (actions)`, `analyze (java-kotlin)`, `analyze (javascript-typescript)`, `analyze (python)`, `analyze (swift)` (the matrix contexts, exactly), `pr-description` `length`; branches must be up to date before merge (`strict_required_status_checks_policy`, so ratchet and budget results compose on `main`); no force push, no deletion, squash only) | a direct push or a merge with a red or missing check | n/a on PR; nightly job compares the live ruleset (`GET /repos/{owner}/{repo}/rulesets`) with the committed file and fails on drift | owner applies once; nightly drift check | seconds |
| R4 Container images | see B4 (Trivy, already planned) | | | |

Kept as is: Dependabot security updates stay off; Renovate opens the fix PRs from Dependabot alerts and OSV (ADR-0031 items 8 and 9). GitHub dependency review on PR is rejected: the dependency graph does not parse the Gradle catalog (ADR-0031), so it would cover npm and actions only; R2 covers all three.

## Owner decisions (2026-11-15, recorded in ADR-0049)

1. Nightly tier and D4: accepted.
2. Coverage: ratchet; fails when coverage of `sdk/sdk-android` or `sdk-ios` drops more than 0.5 point below the committed value. Reverses the coverage rejection of ADR-0047 D13.
3. Retry: none. A flaky gate is fixed at the cause; after two failed fixes the gate is removed and the removal is recorded in ADR-0049.

## Tickets and file ownership

Parallel tickets in one wave touch disjoint files. `AGENTS.md` (table rows), `repo-rules.yml`, and `docs/design-index.md` are shared: each wave has one owner ticket for them, named below; other tickets in the wave hand their row text to it in the PR description.

Wave 1 (parallel):
- 01 Kotlin static analysis: A1, A2, B1, fix D3 line. Files: `sdk/**/build.gradle.kts`, `server/**/build.gradle.kts`, `gradle/libs.versions.toml` files, baselines, verification metadata, `claude-review.yml`. Owner of shared files in wave 1.
- 02 Web lint and budget: W1-W4. Files: `dashboard/`, `perf/report/`, `web-shared/`, `scripts/verify-contract.sh` step 4.
- 03 Shell and workflow lint: D1, D2. Files: `scripts/setup-toolchain.sh`, shell fixes across `scripts/` (does not touch Gradle or web files).
- 04 k6 thresholds: B2. Files: `perf/load.js`, `perf/overload.js`, `perf/run_one.py`, `perf/overload.py`, matching READMEs.

Wave 2 (after wave 1 merges; `nightly.yml` is created by 05 and extended by later tickets in order):
- 05 Nightly workflow + Q1 E2E + Q2 summaries. Owner of `nightly.yml` and shared files in wave 2.
- 06 Android device gates: A3, A5, A6 (A4 after a first run). Files: `sdk/sdk-android/src/androidTest/**`, `scripts/device-durability.sh`.
- 07 iOS gates: I1, I2. Files: `scripts/verify-ios.sh`, `scripts/simulator-durability.sh`, `compatibility/consumer-ios/**`.
- 08 Server SLO + image scan: B3, B4. Files: `perf/compose.perf.yml`, a new `perf/ci-slo.sh`, baseline JSON.
- 11 Dependency and repository gates: R1, R2 (PR part), R3 file and the apply command in `docs/releases/README.md`; the owner applies the ruleset. Files: `.github/renovate.json5` (only if the validator finds an error), `osv-scanner.toml`, `.github/rulesets/main.json`, `check` job of `repo-rules.yml` (after ticket 03 merges). The nightly parts of R2 and R3 go into `nightly.yml` after ticket 05.
- 09 Semver checks: A7, I3. Files: `scripts/verify-contract.sh` step 6 and 8 (after 02 merges, which also touches this file).

Wave 3: 10 coverage (if decision 2 = ratchet), D4, ADR-0049 final results table, README pair, `docs/engineering/failure-modes.md` open gaps update.

Each ticket: implementation session (`sonnet` per `docs/agents/models.md`) → `/code-review` with `opus` in a separate subagent with no session context → fixes → PR → `claude-review.yml` round → merge by AGENTS.md rule 7.

## Results (filled from merged PRs only)

| Gate | Threshold | Where it runs | Measured value | Evidence (run ID, file) |
|---|---|---|---|---|
| W1 to W4 web | ESLint strict with `--max-warnings 0`; Prettier `--check`; gzip JS budget = measured + 10%; no `serious` or `critical` axe violation | PR, `verify-contract` step 4 | 0 serious or critical axe violations in 20 Dashboard and 4 report tests; 206 Dashboard tests (186 before) | PR 283; main run [43728461004](https://github.com/HyeokjaeChoi/signaldock/actions/runs/43728461004); `dashboard/bundle-budget.json`, `perf/report/bundle-budget.json` |
| D1, D2 shell and workflow lint | any finding at `--severity=warning` (shellcheck 0.11.0); any actionlint 1.7.12 finding | PR, `check` | first run: 9 shellcheck findings in 5 scripts, 9 actionlint messages in 5 `run:` steps, all fixed, none baselined | PR 282; PR run [43678422518](https://github.com/HyeokjaeChoi/signaldock/actions/runs/43678422518); main run [43742624222](https://github.com/HyeokjaeChoi/signaldock/actions/runs/43742624222) |
| A1, A2, B1, D3 static analysis | Lint warnings are errors; detekt with ktlint rules; baselines only shrink | PR, `sdk-test`, `server-test` | baselines: Lint 11 entries, detekt 132 in `sdk/` and 94 in `server/`; new steps 88 s (`sdk-test`) and 20 s (`server-test`) | PR 284; PR run [43682680191](https://github.com/HyeokjaeChoi/signaldock/actions/runs/43682680191); main run [43785617506](https://github.com/HyeokjaeChoi/signaldock/actions/runs/43785617506); `*/lint-baseline.xml`, `*/detekt-baseline.xml` |
| B2 k6 thresholds | `http_req_failed` above 1% (`load.js`); status outside {200, 503} or `dropped_iterations` above 0 (`overload.js`) | every run of both scripts | 9 of 9 proof cases matched; no committed result crosses a threshold | PR 287; main run [43746460471](https://github.com/HyeokjaeChoi/signaldock/actions/runs/43746460471); `perf/threshold-proof/results.md`, `perf/threshold-proof/runner-runs.md` |
| Q1, Q2 E2E and summaries | any case 1 to 5 fails | Nightly, `e2e-delivery` | cases 1 to 5 pass; job time 12 min 10 s and 11 min 25 s | PR 288; runs [43683069360](https://github.com/HyeokjaeChoi/signaldock/actions/runs/43683069360), [43691646307](https://github.com/HyeokjaeChoi/signaldock/actions/runs/43691646307); main run [43749303246](https://github.com/HyeokjaeChoi/signaldock/actions/runs/43749303246) |
| A7, I3 semver | removed or changed API line without a MAJOR (MINOR before 1.0) bump | PR, `verify-contract` steps 6 and 8 | `scripts/check-api-semver.test.sh`: unchanged, added, removed, changed, patch bump, minor bump, for both platforms | PR 290; main run [43758595354](https://github.com/HyeokjaeChoi/signaldock/actions/runs/43758595354) |
| A3 to A6 Android device | StrictMode violation; lost or unreported event; corrupt queue, duplicate, `conflict`, or loss before the checkpoint; `track()` p99 above 170,000 microseconds | PR `device-test` (A3); Nightly `device-durability`, API 24 and 37.0 | A3 passed; 13 of 13 cases at API 24 and at 37.0; A6 0 lost before the checkpoint; A4 p99 from 4,143 to 84,401 microseconds in 17 values | PR 291; `device-test` run [43743427552](https://github.com/HyeokjaeChoi/signaldock/actions/runs/43743427552); runs [43695424448](https://github.com/HyeokjaeChoi/signaldock/actions/runs/43695424448), [43749861972](https://github.com/HyeokjaeChoi/signaldock/actions/runs/43749861972), [43749868768](https://github.com/HyeokjaeChoi/signaldock/actions/runs/43749868768), [43749875422](https://github.com/HyeokjaeChoi/signaldock/actions/runs/43749875422); main run [43779657000](https://github.com/HyeokjaeChoi/signaldock/actions/runs/43779657000) |
| I1, I2 iOS | a Main Thread Checker report; any lost or unreported event | PR `ios-test` and `ios-sanitize` (I1); Nightly `ios-durability` (I2) | I1: with a sample the CI compiler run exited 1 with the checker message; I2: 7 of 7 cases in 567 s and 783 s | PR 294; runs [43695114331](https://github.com/HyeokjaeChoi/signaldock/actions/runs/43695114331), [43691633861](https://github.com/HyeokjaeChoi/signaldock/actions/runs/43691633861); main run [43761449073](https://github.com/HyeokjaeChoi/signaldock/actions/runs/43761449073) |
| B3, B4 server | p95 above 2 x the committed 11.61 ms or any non-200 at 20 req/s x 20 events for 120 s (B3); Critical or High with a fix (B4) | Nightly `ingest-slo`; PR `image-scan` | B3: p95 8.16 ms and 11.40 ms, 2,401 requests, no bad request; B4: 2 and 10 HIGH findings on the old Dockerfiles (fixed), 7 HIGH on the bad sample | PR 296; runs [43780134683](https://github.com/HyeokjaeChoi/signaldock/actions/runs/43780134683), [43782066629](https://github.com/HyeokjaeChoi/signaldock/actions/runs/43782066629), [43777643246](https://github.com/HyeokjaeChoi/signaldock/actions/runs/43777643246), [43780060680](https://github.com/HyeokjaeChoi/signaldock/actions/runs/43780060680); main run [43790536140](https://github.com/HyeokjaeChoi/signaldock/actions/runs/43790536140); `perf/ci-slo-baseline.json` |
| R1 to R3 dependencies and ruleset | `renovate-config-validator --strict`; OSV finding not in `osv-scanner.toml` (ignore at most 30 days); `main` ruleset equal to `.github/rulesets/main.json` | PR `check`, `osv-scan`; Nightly `ruleset-drift` | first OSV scan of main: 196 findings of 55 advisories (54 Maven, 1 npm) | PR 295; main run [43794849318](https://github.com/HyeokjaeChoi/signaldock/actions/runs/43794849318); `.github/rulesets/main.json`, `osv-scanner.toml` |
| Q3 coverage | more than 0.5 point below or above the committed value | PR `sdk-test` (Android, JVM unit tests), `ios-test` | pending: the first measured values and run ID follow in the PR that resolves ticket 10 | PR for ticket 10; `scripts/coverage-baseline.json` |
| D4 release needs green nightly | last `nightly.yml` run on `GITHUB_SHA` is not `success` | Release, `build` job | pending: no tag has been pushed since the gate; `scripts/check-nightly-green.test.sh` covers success, failure, cancelled, running, no run, and API error | PR for ticket 10 |
