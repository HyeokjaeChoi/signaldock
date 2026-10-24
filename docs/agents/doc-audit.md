# Doc audit: SignalDock rules

Check the Markdown docs against the code at every release and after a batch of merges. This file holds the repo facts for that check. Any audit tool or agent follows it; the general procedure (parallel read-only audit, re-verify each finding, one PR) is in AGENTS.md "Judgment rules".

## Who decides

- Descriptive docs (README, guides, `TOOLCHAIN.md`, `CONTRIBUTING.md`, engineering docs): the code, build files, scripts, workflows, `openapi/`, migrations, and fixtures are the source of truth. Fix the doc.
- Design sources (`architecture.md`, ADRs, `.scratch/*/spec.md` Q decisions, `docs/design-index.md`; see `.coderabbit.yaml`): if the code disagrees, do not rewrite either one. Report it, and resolve it under AGENTS.md "Judgment rules" 2 (fix the code, or record a new decision in an ADR). Status, evidence, and date lines in `architecture.md` can follow the code.

## What to verify

| Check | Source of truth |
| --- | --- |
| Versions (SDK, toolchain, pins, platform levels) | `sdk/sdk-android/build.gradle.kts`, `gradle/libs.versions.toml`, Dockerfiles, `package.json` |
| Commands and flags | `scripts/`, `.githooks/`, `.github/workflows/` |
| Paths and links | `test -e` for every relative link and path |
| Endpoints, fields, limits | `openapi/`, `fixtures/`, server and SDK constants |
| Public API signatures | `sdk/sdk-android/api/*.api` |
| Config options and log strings | SDK and server source |
| Room schema | `sdk/sdk-android/schemas/` |
| CI job names, counts, and runs | `.github/workflows/`, `gh run view` (cite the run) |
| Test counts | Count them (`grep -c`, test runner list mode) |
| Feature lists and release notes | `CHANGELOG.md`, `git log <last-tag>..HEAD` |
| "Planned", "pending", "not started", "not verified", "no physical device", `Gap:` lines | Merged work, issue files in `.scratch/*/issues/` (`Execution:` lines) |
| Lists of controls (CI and supply-chain rows, threat-model mitigations) | `.github/workflows/`, every accepted ADR |
| ADR lists in `docs/design-index.md` | `docs/adr/` |
| `.coderabbit.yaml` `path_instructions` | Top-level directories, `docs/design-index.md` sections |
| English and Korean parity | `README.md` and `README.ko.md`; `docs/integration-guide.md` and `.ko.md` |
| Refresh stamps (date, PR list) in `docs/engineering/` | `git log` since the stamp; dates in UTC |

Doc set: `README*`, `architecture.md`, `GLOSSARY.md`, `TOOLCHAIN.md`, `SECURITY.md`, `CONTRIBUTING.md`, `AGENTS.md`, `CHANGELOG.md`, `docs/` (except the exclusions), the onepager (`.md` and `.html` agree), `.coderabbit.yaml`.

## Do not change

- ADR decision text. Add a new ADR, or a dated `## Amendment` section as in ADR-0021.
- Postmortems, perf results, research notes, dated historical rows (for example the `TOOLCHAIN.md` issue #6 row and old test counts).
- Released sections of `CHANGELOG.md`, except for a factual error. `[Unreleased]` can change.
- Issue records of other work.

## Issue files

- Every `claimed` issue whose PR merged (`Execution:` line, `git log origin/main --grep`): set `Status: resolved` and an `Execution: Done` line with the PR number and the UTC date.
- An `Execution: Done` line needs `Status: resolved` (`scripts/check-repo-rules.sh` checks it).
- Record the audit itself in one new issue file, `resolved` in the same PR. Take the next free number after you check open PRs for new issue files (`gh pr list`, PR file lists), so numbers do not collide.

## Verification

- `scripts/check-repo-rules.sh` (check the real exit code).
- `scripts/verify-contract.sh` directly: the pre-push hook skips it when only Markdown, `docs/`, and `.scratch/` files changed. State in the PR any step that did not run.

## Ideas for scripted checks (not built)

- Fail when a relative link or path in a tracked Markdown file does not exist.
- Compare the CI job names in docs with `.github/workflows/*.yml`.
- Compare the ADR list in `docs/design-index.md` with `docs/adr/`.
- Compare `.coderabbit.yaml` `path_instructions` with the top-level directories.
- Compare version strings in docs with `sdkVersion` and `libs.versions.toml`.
- Fail when a `Gap:` line names an issue file whose status is `resolved`.
