# SignalDock

A mobile event collection SDK and a data processing server in one monorepo.
Hold the project to production open-source standards.

## Communication

Explain answers to the user in Korean. Keep technical terms and proper names in their original language.
Use short, clear sentences. State the conclusion first and ask one question at a time.
When writing English, follow the simple, clear language principles of ASD-STE100.

## Commands

- Once per clone: `git config core.hooksPath .githooks`. Pre-commit runs `scripts/check-repo-rules.sh`. Pre-push also runs `scripts/verify-contract.sh` when code changed; it needs `ANDROID_HOME`, `ANDROID_SDK_ROOT`, or `sdk.dir` in `sdk/local.properties` (ADR-0019). On macOS it also runs the iOS gates when full Xcode with an iOS Simulator runtime exists, else it skips them.
- Before you open a PR: run the Claude Code `/code-review` skill on your branch, and fix its findings.

## Enforced rules

Code enforces these. Do not restate them in prose; read the check when it fails.

| Rule | Enforced by |
|---|---|
| Scripts and hooks that CI runs are executable (100755) | `scripts/check-repo-rules.sh` |
| Every issue or PR reference with `#` in Markdown has a number | `scripts/check-repo-rules.sh` |
| Issue `Status:` is a triage name, `claimed`, or `resolved`; an `Execution: Done` line names a numbered PR and needs `Status: resolved` | `scripts/check-repo-rules.sh` |
| No raw HTML sinks in `dashboard/src`, `perf/report/src`, `web-shared` | `scripts/check-repo-rules.sh` |
| `README.md` and `README.ko.md` stay in step | `scripts/check-repo-rules.sh` |
| Every top-level directory except `.claude`, `.scratch`, and `docs` has a section in `docs/design-index.md` | `scripts/check-repo-rules.sh` |
| A released Room schema file is frozen (ADR-0025) | `scripts/check-repo-rules.sh` |
| A released iOS SQL migration file in `sdk-ios/Sources/SignalDock/Migrations` is frozen (ADR-0047 D7) | `scripts/check-repo-rules.sh` |
| `SDK_VERSION` equals `sdkVersion` | `scripts/check-repo-rules.sh` |
| `sdk-ios/Sources` uses no required-reason API (file timestamp, boot time, disk space, `UserDefaults`, active keyboards) whose category is missing in `PrivacyInfo.xcprivacy` (ADR-0047 D14) | `scripts/check-repo-rules.sh` |
| `sdk-ios/Package.swift` has no `.package(` dependency (ADR-0047 D13) | `scripts/check-repo-rules.sh` |
| No `privacy: .public` under `sdk-ios` (ADR-0047 D13) | `scripts/check-repo-rules.sh` |
| iOS SDK gates: `swift format` strict with documentation on public declarations, Simulator tests under TSan and under ASan and UBSan with no Main Thread Checker report in the log (ADR-0049 I1), `xcodebuild analyze` (ADR-0047 D13) | `scripts/verify-ios.sh`, CI `ios-test` and `ios-sanitize` |
| Every `uses:` in `.github/workflows` and every tracked `action.yml` or `action.yaml` is pinned by a 40-character SHA (local `./` actions excepted) | `scripts/check-repo-rules.sh` |
| No workflow triggers on `pull_request_target` or `workflow_run` | `scripts/check-repo-rules.sh` |
| Every workflow has a top-level `permissions:` key | `scripts/check-repo-rules.sh` |
| `demo-deploy.yml` runs only on `push` of `demo-v*` tags; `release-ios.yml` runs only on `push` of `ios-v*` tags (ADR-0047 D11) | `scripts/check-repo-rules.sh` |
| `sdk-ios/Package.swift` has the `SignalDockTests` line exactly as `scripts/build-ios-release-zip.sh` removes it for the release ZIP (ADR-0047 Ticket 13) | `scripts/check-repo-rules.sh` (`--check-manifest`) |
| `demo-deploy.yml` has no `id-token`, `write-all`, or `secrets` context | `scripts/check-repo-rules.sh` |
| `nightly.yml` triggers only on `schedule` and `workflow_dispatch`; every job in it runs `scripts/nightly-summary.py` (ADR-0049 gate Q2; format in `docs/engineering/nightly-summary.md`) | `scripts/check-repo-rules.sh` |
| `.github/renovate.json5` passes `renovate-config-validator --strict` with the pinned `renovate` version (ADR-0049 R1) | CI job `check` (`.github/workflows/repo-rules.yml`) |
| No known vulnerability in the npm lockfiles and the four `gradle/verification-metadata.xml` files unless the `osv-scanner.toml` of its own folder ignores it (ADR-0049 R2) | `scripts/osv-scan.sh`, CI job `osv-scan`, `nightly.yml` job `osv-scan` |
| Every `osv-scanner.toml` sits next to a listed lockfile and has only `[[IgnoredVulns]]` entries, each with a reason and an `ignoreUntil` at most 30 days ahead; every tracked npm lockfile and `verification-metadata.xml` is listed in `scripts/osv-scan.sh`; the OSV-Scanner version is set only in `scripts/setup-toolchain.sh` | `scripts/check_osv_config.py` in `scripts/check-repo-rules.sh`; `scripts/test_check_osv_config.py` in CI job `check` |
| The required contexts of `.github/rulesets/main.json` are exactly the PR jobs of `repo-rules.yml` and `pr-description.yml` and the `analyze (<language>)` matrix of `codeql.yml`; the ruleset has squash only, up-to-date branches, no force push, no deletion (ADR-0049 R3) | `scripts/check_ruleset.py static` in `scripts/check-repo-rules.sh`; `scripts/test_check_ruleset.py` in CI job `check` |
| The live `main` ruleset contains what `.github/rulesets/main.json` says (the owner applies the file, `docs/releases/README.md`) | `nightly.yml` job `ruleset-drift` (`scripts/check_ruleset.py drift`) |
| Contract and generated code: codegen drift, `oasdiff` baseline, SDK public API dump, SDK dependency baseline, tracking plan codegen | `scripts/verify-contract.sh` (pre-push, CI `verify-contract`) |
| Semver of the public API dumps (ADR-0049 A7, I3): a removed or changed line in `sdk-android.api` or `SignalDock.swiftinterface` against the last `v*` or `ios-v*` tag needs a new MAJOR (before 1.0, MINOR) in `sdkVersion` or `SignalDockSDK.version` | `scripts/check-api-semver.sh` in `scripts/verify-contract.sh` steps 6 and 8 (pre-push, CI `verify-contract`) |
| Nightly Android durability: all cases of `scripts/device-durability.sh` at API 24 and 37.0, including power loss (A6: integrity, no loss before the checkpoint, `duplicate` not `conflict`) and the main-thread `track()` p99 budget (A4) (ADR-0049 A4, A5, A6) | `scripts/device-durability.sh`, `nightly.yml` job `device-durability` |
| `StrictMode` with `penaltyDeath` on the main thread around public SDK calls in the PR tier (ADR-0049 A3) | `MainThreadStrictMode` rule in `device-test` |
| Nightly iOS Simulator durability: all seven cases of `scripts/simulator-durability.sh`, no lost or unreported event (ADR-0049 I2) | `scripts/simulator-durability-ci.sh`, `nightly.yml` job `ios-durability` |
| iOS SDK public API: `sdk-ios/api/SignalDock.swiftinterface` equals the normalized interface of a check-only build with the compiler of CI (`sdk-ios/api/compiler-version`); take updates from the CI artifact `api-actual` with `scripts/verify-ios.sh api-update <dir>` | CI `ios-test` (`scripts/verify-ios.sh api`); on a Mac with another compiler `scripts/verify-contract.sh` step 8 skips it |
| Android Lint on every `sdk/` Android module that has a lint task: `warningsAsErrors`, `abortOnError`, a committed `lint-baseline.xml` in each (ADR-0049) | `sdk/build.gradle.kts`, CI `sdk-test` (`lint`) |
| detekt with the ktlint rule set on every `sdk/` and `server/` Kotlin module, `gradle/detekt.yml`, a committed `detekt-baseline.xml` in each module that has findings (ADR-0049) | `sdk/build.gradle.kts`, `server/build.gradle.kts`, CI `sdk-test` and `server-test` (`detekt`) |
| A lint or detekt baseline has no more entries of a rule in a file than at the merge base with `origin/main` (a baseline only shrinks; a new module starts empty) | `scripts/check-baselines.py` via `scripts/check-repo-rules.sh`; `scripts/test_check_baselines.py` |
| Line coverage of `sdk/sdk-android` (JVM unit tests, job `sdk-test`) and `sdk-ios` (job `ios-test`) is within 0.5 point of the value in `scripts/coverage-baseline.json`, below and above (a rise is committed in the same PR) (ADR-0049 decision 4) | `scripts/check-coverage.py android\|ios`, CI `sdk-test` and `ios-test` |
| The committed coverage value is not lower than at the merge base with `origin/main`, unless the same diff sets `lowered: {from, reason}` next to it | `scripts/check-coverage.py ratchet` via `scripts/check-repo-rules.sh`; `scripts/test_check_coverage.py` |
| A release `build` job fails unless the last `nightly.yml` run on the tagged commit is `success`; the job alone has `actions: read` (ADR-0049 decision 8, gate D4) | `scripts/check-nightly-green.sh` in `release.yml` and `release-ios.yml`; `scripts/check-nightly-green.test.sh` in `scripts/verify-contract.sh` |
| Server images: Trivy finds no Critical or High finding with a fix in the server and Dashboard images; runs only when a Dockerfile or a dependency file of an image changes (ADR-0049 B4) | `.github/workflows/image-scan.yml` (not a required check: a path-filtered workflow that does not run would block the merge) |
| Nightly ingest load SLO at 20 req/s x 20 events for 120 s on Compose: no bad request, p95 at most 2x `perf/ci-slo-baseline.json` of the same runner class (ADR-0049 B3, decision 7) | `perf/ci-slo.sh`, `perf/ci_slo.py`, `nightly.yml` job `ingest-slo` |
| PR description is 3,500 characters or less (author text only; Dependabot and Renovate PRs exempt) | `.github/workflows/pr-description.yml` |
| Builds, tests, device tests, SDK size budget (Android `size-budget`, iOS `ios-size-budget`), Gradle wrapper checksums | `.github/workflows/repo-rules.yml` |

When a mistake or the same kind of review finding repeats, add a check to `scripts/check-repo-rules.sh` or `scripts/verify-contract.sh` and a row here, not a sentence.

## Judgment rules

1. Delegate research and implementation (product code, tests, build configuration, docs) to a lower-tier model; design, grilling, and planning stay in the top-tier session. Follow the step-to-model table in `docs/agents/models.md` (set by the owner on 2026-10-25). If the listed model fails or is not available, use the next higher tier; if the top tier fails, stop and report. Say so in the report. The main agent defines the question, splits the work, tracks progress, reviews the results, and applies research findings to the design.
2. Build what the design sources say. If you must make a new decision, record it in an ADR in the same PR and name it in the PR description. Implementers do not write review rules.
3. One purpose per PR. Work outside it, such as new hooks, CI, or review setup, goes in a separate PR.
4. A PR description states what changed, which design sections it follows, and the exact verification commands and results. Never label a step verified without a run.
5. After you open a PR, add the label `claude-review-request`. Claude Code then reviews the PR against the design sources with no session context (`.github/workflows/claude-review.yml`, ADR-0041). Answer each finding in one way: fix the code; fix the design source in the same PR; add a check to `scripts/check-repo-rules.sh` or `scripts/verify-contract.sh`; or reject it and cite the design source section.
6. Collect all findings of one review round, fix them together, and push once. When the review job has ended and removed the label, add it again to start the next review.
7. Merge when no Critical or Major finding is open and all checks pass. Record each open Minor or lower finding as a local issue, link it in the PR, and do not hold the merge for it.

## Where things are

- If `AGENTS.local.md` exists, read it. It holds this machine's personal setup and is not committed.
- Before you delegate work to a model, read `docs/agents/models.md`.
- Before you edit or review files in an area, read the sections that `docs/design-index.md` lists for it.
- Before you explore code or design the domain, read `docs/agents/domain.md` (start with one domain context).
- Before you create or read an issue, or evaluate or change its status (local Markdown files), read `docs/agents/issue-tracker.md` and `docs/agents/triage-labels.md`.
- At every release and after a batch of merges, check the Markdown docs against the code. Before you do, read `docs/agents/doc-audit.md`.
