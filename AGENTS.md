# SignalDock

## Project purpose

Build a mobile event collection SDK and a data processing server in one monorepo.
Hold the project to production open-source standards.

## Communication

Explain answers to the user in Korean. Keep technical terms and proper names in their original language.
Use short, clear sentences. State the conclusion first and ask one question at a time.
When writing English, follow the simple, clear language principles of ASD-STE100.

## Research models

When the user requests research, delegate source research to an available low-cost model.
Model names mentioned by the user are examples, not fixed requirements.
The main agent defines the question, reviews the findings, and applies them to the design.
If one model is unavailable, choose another suitable low-cost model.

## Implementation models

Prefer a suitable available low-cost model for product code, tests, and build configuration.
Model names mentioned by the user are examples. Do not require a fixed model or approval for each model choice.
The main agent splits the work, tracks progress, and reviews results.
If one example model fails, try another suitable model before stopping implementation.

## Local setup

If `AGENTS.local.md` exists, read it. It holds personal setup for one machine, such as which agent does which work. It is not committed.

## Agent skills

### Issue tracker

Keep issues and specs in local Markdown files.
Before creating or reading an issue, read `docs/agents/issue-tracker.md`.

### Triage labels

Use the five default status names, `claimed` while work is on a branch or in an open PR, and `resolved` for merged work.
Before evaluating or changing an issue's status, read `docs/agents/triage-labels.md`.

### Domain docs

Start with one domain context.
Before exploring code or designing the domain, read `docs/agents/domain.md`.

## Documentation upkeep

At every release, and when docs may have drifted from the code, run the `update-internal-docs` skill (`.claude/skills/update-internal-docs/SKILL.md`).
After a batch of merges, run the `signaldock-stale-cleanup` skill (`.claude/skills/signaldock-stale-cleanup/SKILL.md`). It extends `update-internal-docs`.

## Setup

Run once per clone: `git config core.hooksPath .githooks`.
The pre-commit hook runs `scripts/check-repo-rules.sh`. The pre-push hook also runs `scripts/verify-contract.sh` when code changed; it needs `ANDROID_HOME`, `ANDROID_SDK_ROOT`, or `sdk.dir` in `sdk/local.properties` for the SDK API check (ADR-0019).

## Roles

- Implementer (local agents): before you edit files in an area, read the sections that `docs/design-index.md` lists for it. Build what the design sources say. If you must make a new decision, record it in an ADR in the same PR and name it in the PR description. Do not write review rules.
- Reviewer (CodeRabbit): it reviews with no session context against the design sources. Its configuration is `.coderabbit.yaml`. When you answer a review finding, do one of these: fix the code; fix the design source in the same PR; add a check to `scripts/check-repo-rules.sh` or `scripts/verify-contract.sh`; or reject the finding and cite the design source section. Learnings only calibrate CodeRabbit. Do not rely on them for design facts.
- PR descriptions state what changed, which design sections it follows, and the exact verification commands and results. Never label a step verified without a run.

## Review workflow

1. Keep one purpose per PR. If a review asks for work outside that purpose, such as new hooks, CI, or review setup, put it in a separate PR.
2. Before you open a PR, run `coderabbit review --base main --deep --agent` and fix its findings. The PR review then confirms the work.
3. Collect all findings of one review round and fix them together. Push once, then request one `@coderabbitai full review`.
4. If the same kind of finding comes back, add a check to `scripts/check-repo-rules.sh` or `scripts/verify-contract.sh` instead of fixing it by hand again.
5. Merge criterion: a PR is ready to merge when no Critical or Major finding is open and all checks pass. Record each open Minor or lower finding as a local issue (see `docs/agents/issue-tracker.md`), link it in the PR, and do not hold the merge for it.
