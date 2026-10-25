# SignalDock

A mobile event collection SDK and a data processing server in one monorepo.
Hold the project to production open-source standards.

## Communication

Explain answers to the user in Korean. Keep technical terms and proper names in their original language.
Use short, clear sentences. State the conclusion first and ask one question at a time.
When writing English, follow the simple, clear language principles of ASD-STE100.

## Commands

- Once per clone: `git config core.hooksPath .githooks`. Pre-commit runs `scripts/check-repo-rules.sh`. Pre-push also runs `scripts/verify-contract.sh` when code changed; it needs `ANDROID_HOME`, `ANDROID_SDK_ROOT`, or `sdk.dir` in `sdk/local.properties` (ADR-0019).
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
| `SDK_VERSION` equals `sdkVersion` | `scripts/check-repo-rules.sh` |
| Every `uses:` in `.github/workflows` is pinned by a 40-character SHA (local `./` actions excepted) | `scripts/check-repo-rules.sh` |
| No workflow triggers on `pull_request_target` or `workflow_run` | `scripts/check-repo-rules.sh` |
| Every workflow has a top-level `permissions:` key | `scripts/check-repo-rules.sh` |
| `demo-deploy.yml` runs only on `push` of `demo-v*` tags | `scripts/check-repo-rules.sh` |
| `demo-deploy.yml` has no `id-token:` and no `secrets.` | `scripts/check-repo-rules.sh` |
| Contract and generated code: codegen drift, `oasdiff` baseline, SDK public API dump, SDK dependency baseline, tracking plan codegen | `scripts/verify-contract.sh` (pre-push, CI `verify-contract`) |
| PR description is 3,500 characters or less (author text only; Dependabot and Renovate PRs exempt) | `.github/workflows/pr-description.yml` |
| Builds, tests, device tests, SDK size budget, Gradle wrapper checksums | `.github/workflows/repo-rules.yml` |

When a mistake or the same kind of review finding repeats, add a check to `scripts/check-repo-rules.sh` or `scripts/verify-contract.sh` and a row here, not a sentence.

## Judgment rules

1. Delegate research and implementation (product code, tests, build configuration, docs) to a lower-tier model; design, grilling, and planning stay in the top-tier session. Follow the step-to-model table in `docs/agents/models.md` (set by the owner on 2026-10-25). If the listed model fails or is not available, use the next higher tier; if the top tier fails, stop and report. Say so in the report. The main agent defines the question, splits the work, tracks progress, reviews the results, and applies research findings to the design.
2. Build what the design sources say. If you must make a new decision, record it in an ADR in the same PR and name it in the PR description. Implementers do not write review rules.
3. One purpose per PR. Work outside it, such as new hooks, CI, or review setup, goes in a separate PR.
4. A PR description states what changed, which design sections it follows, and the exact verification commands and results. Never label a step verified without a run.
5. Claude Code reviews each PR against the design sources with no session context (`.github/workflows/claude-review.yml`, ADR-0041). Answer each finding in one way: fix the code; fix the design source in the same PR; add a check to `scripts/check-repo-rules.sh` or `scripts/verify-contract.sh`; or reject it and cite the design source section.
6. Collect all findings of one review round, fix them together, and push once. The push starts the next review.
7. Merge when no Critical or Major finding is open and all checks pass. Record each open Minor or lower finding as a local issue, link it in the PR, and do not hold the merge for it.

## Where things are

- If `AGENTS.local.md` exists, read it. It holds this machine's personal setup and is not committed.
- Before you delegate work to a model, read `docs/agents/models.md`.
- Before you edit or review files in an area, read the sections that `docs/design-index.md` lists for it.
- Before you explore code or design the domain, read `docs/agents/domain.md` (start with one domain context).
- Before you create or read an issue, or evaluate or change its status (local Markdown files), read `docs/agents/issue-tracker.md` and `docs/agents/triage-labels.md`.
- At every release and after a batch of merges, check the Markdown docs against the code. Before you do, read `docs/agents/doc-audit.md`.
