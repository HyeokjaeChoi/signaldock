---
name: update-internal-docs
description: Find docs that drifted from the code and fix them. Run at every release and on demand. Use when the user says "release", "cut a release", "update docs", "stale docs", "docs drift", or "문서 점검".
---

# Update internal docs

For descriptive docs (README, guides, toolchain, engineering docs), code, build files, scripts, workflows, `openapi/`, migrations, and fixtures are the source of truth: fix the doc. Design sources (`architecture.md`, ADRs, `.scratch/*/spec.md` Q decisions, `docs/design-index.md`; see `.coderabbit.yaml`) are different. In `architecture.md`, you can update status and evidence lines from the code. If the code disagrees with a design source, do not rewrite either one here. Report it as a possible implementation error and resolve it under AGENTS.md "Roles" (fix the code, or record a new decision in an ADR) before you change the docs.

## Scope: what to verify

| Check | Source of truth |
| --- | --- |
| Versions (SDK, toolchain, pins, platform levels) | `sdk/sdk-android/build.gradle.kts`, `gradle/libs.versions.toml`, `TOOLCHAIN.md` |
| Commands and flags | `scripts/`, `.githooks/`, `.github/workflows/` |
| Paths and links | `test -e` for every relative link and path in the docs |
| Endpoints, fields, limits | `openapi/`, `fixtures/`, server and SDK constants |
| Public API signatures | `sdk/sdk-android/api/*.api` |
| Config options and log strings | SDK and server source |
| Room schema | `sdk/sdk-android/schemas/` |
| CI job names, counts, and runs | `.github/workflows/`, `gh run view` (cite the run) |
| Feature lists and release notes | `CHANGELOG.md`, `git log <last-tag>..HEAD` |
| "Planned", "pending", "not started", `Gap:` lines | Merged work, issue files in `.scratch/*/issues/` |
| ADR lists in `docs/design-index.md` | `docs/adr/` |
| `.coderabbit.yaml` `path_instructions` | Top-level directories, `docs/design-index.md` sections |
| English and Korean parity | `README.md` and `README.ko.md`; `docs/integration-guide.md` and `.ko.md` |
| Refresh stamps (date, PR list) in `docs/engineering/` | `git log` since the stamp; dates in UTC |

Doc set: `README*`, `architecture.md`, `GLOSSARY.md`, `TOOLCHAIN.md`, `SECURITY.md`, `CONTRIBUTING.md`, `AGENTS.md`, `CHANGELOG.md`, `docs/` (except the exclusions), the onepager (`.md` and `.html` agree), `.coderabbit.yaml`.

## Exclusions: do not change

- ADR decision text (records). Add a new ADR, or a dated `## Amendment` section as in ADR-0021, postmortems, perf results.
- Dated historical rows, for example the `TOOLCHAIN.md` issue #6 row and old test counts.
- Released sections of `CHANGELOG.md`, except for a factual error.
- Issue records of other work.

## Procedure

1. Fan out read-only audits by doc group, in parallel, with low-cost subagents (AGENTS.md "Research models"). Each returns lines of `file:line | stale claim | evidence in the source of truth | fix`.
2. Re-verify every finding yourself. Skip a finding the code contradicts. Apply minimal fixes in one docs PR, with its own issue file. Rules for engineering docs: every claim cites a file, ADR, spec Q, test, or result; gaps are marked `Gap:`; dates are UTC. Write English in ASD-STE100 style.
3. Run `scripts/check-repo-rules.sh`. Run `scripts/verify-contract.sh` directly. The pre-push hook skips it when only Markdown, `docs/`, and `.scratch/` files changed. State in the PR any step that did not run.
4. Run `coderabbit review --base main --deep --agent`. Fix the findings.
5. Open the PR as AGENTS.md "Review workflow" says. List fixed and skipped items, with reasons. A docs-only PR may merge when the review is clean.

## Ideas for scripted checks (not built)

- Fail when a relative link or path in a tracked Markdown file does not exist.
- Compare the CI job names in docs with `.github/workflows/*.yml`.
- Compare the ADR list in `docs/design-index.md` with `docs/adr/`.
- Compare `.coderabbit.yaml` `path_instructions` with the top-level directories.
- Compare version strings in docs with `sdkVersion` and `libs.versions.toml`.
- Fail when a `Gap:` line names an issue file whose status is `resolved`.
