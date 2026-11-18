# 93. Doc audit before Android SDK v0.4.1

Status: resolved
Type: task
Blocked by: None
Execution: Done (PR #314, 2026-11-18 UTC)

## Scope and completion criteria

`docs/releases/README.md` step 2 and `docs/agents/doc-audit.md`, at `main` @ `9a804f0` (2026-11-18 UTC), after the ADR-0049 gates and follow-ups (PRs 282 to 312). PR 313 owns the version lines (README pair, integration guides, `architecture.md`, onepager, `CHANGELOG.md`, `docs/releases/v0.4.1.md`); this audit leaves them.

Method: the Haiku subagents of "Procedure" step 1 could not start (the session refused to start a subagent from a subagent), so the main agent did the audit itself, read-only first, and checked each finding against the code before it edited. Not delegated: say so in the PR.

## Fixed

1. `README.md`, `README.ko.md`, CI bullet: lists `osv-scan` (`repo-rules.yml`), the `image-scan` workflow (`trivy` job), and all six nightly jobs (`e2e-delivery`, `device-durability`, `ios-durability`, `ingest-slo`, `osv-scan`, `ruleset-drift`; `.github/workflows/nightly.yml`). The claim "the process-boundary cases ... stay local" was false since PR 291 and PR 294: they run in the nightly jobs.
2. Same bullet: `device-test` count 33 start, 16 skip, 17 run (run 39032066203, 2026-10-17) is now 38, 20, 18 (count of `@Test` in `sdk/sdk-android/src/androidTest` and `src/measure`: 9 + 3 + 3 + 5 phase tests skip; ADR-0049 ticket 06 amendment). The CI job log was not re-read.
3. `README.md:93`, `README.ko.md:95` supply-chain row: adds OSV-Scanner and the Trivy image scan.
4. `README.md:130`, `README.ko.md:132`, `CONTRIBUTING.md:13`, `CONTRIBUTING.md` hooks list, `AGENTS.md` Commands: name shellcheck, actionlint, osv-scanner and the `check-shell-lint.sh` run of `.githooks/pre-commit` (closes issue 12).
5. `TOOLCHAIN.md`: the composite action `.github/actions/toolchain` (`.tools` cache, modes, `cache: 'false'` in `release.yml`); the Trivy row names `image-scan.yml`; a line for OSV-Scanner.
6. `docs/engineering/threat-model.md`: the fault cases run in CI nightly at API 24 and 37.0 (was "CI does not run them"); the supply-chain and MASVS CODE rows name `osv-scan`, Trivy, and the ruleset-as-code with its drift job; the Gap for a transitive Gradle dependency is narrowed.
7. `docs/engineering/README.md` refresh stamp: PRs #288, #299, #302 and this audit.
8. `architecture.md` evidence table (status line): nightly `device-durability` and `ios-durability`.
9. Issue files: 11 (PR #295), 15 (PR #306), 18 (PR #303) set to `resolved`; 12 resolved by this PR.

## Kept, with reasons

- `docs/adr/0023-...md:36` ("API 24 is not run in CI") and `docs/adr/0010-...md` decision 3 text: ADR decision text. ADR-0049 amends them; a dated amendment in each already exists. Not edited.
- `.scratch/quality-gates/spec.md` results row D4 "pending: no tag has been pushed since the gate": true until the next tag; the spec is a design source.
- `docs/engineering/failure-modes.md` "Still open" 1 and 8: already follow ADR-0049 (nightly `powerloss`, `device-durability` at API 24 and 37.0).
- Issues 13 (JDK lookup), 21 (device-durability fixed delay), `07-followup` (flaky test): still true in the code, still open.
- `docs/adr/0049-...md` line 15 (context: "local only"): history of the problem the ADR solves.
- Dated rows, postmortems, perf results, released `CHANGELOG.md` sections: not changed (`doc-audit.md` "Do not change").

## Design-source mismatches for the owner (not edited)

- `docs/adr/0023-ci-device-tests-and-size-budget.md` "Limits" line 36 ("API 34 only. API 24 (minSdk) is not run in CI") against the nightly `device-durability` job at API 24 and 37.0. It stays true for the PR tier (`device-test`). The ADR-0023 amendment of 2026-11-15 does not name the nightly API 24 run; ADR-0029 does (line 24). Owner: add one sentence to the ADR-0023 amendment, or accept ADR-0029 as the place.

## Verification

- Every relative link in tracked Markdown files outside `results/` exists (script run, 0 broken).
- Every `docs/adr/NNNN` has an `ADR-NNNN` mention in `docs/design-index.md`.
- `scripts/check-repo-rules.sh`; `scripts/verify-contract.sh`: see the PR description.
