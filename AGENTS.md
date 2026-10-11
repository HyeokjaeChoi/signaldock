# SignalDock

## Project purpose

Build a mobile event collection SDK and a data processing server in one monorepo.
Use the project as a portfolio for the Datarize Mobile SDK Engineer role.

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

## Agent skills

### Issue tracker

Keep issues and specs in local Markdown files.
Before creating or reading an issue, read `docs/agents/issue-tracker.md`.

### Triage labels

Use the five default status names.
Before evaluating or changing an issue's status, read `docs/agents/triage-labels.md`.

### Domain docs

Start with one domain context.
Before exploring code or designing the domain, read `docs/agents/domain.md`.

## Setup

Run once per clone: `git config core.hooksPath .githooks`.
The pre-commit hook runs `scripts/check-repo-rules.sh`. The pre-push hook also runs `scripts/verify-contract.sh` when code changed.

## Roles

- Implementer (local agents): before you edit files in an area, read the sections that `docs/design-index.md` lists for it. Build what the design sources say. If you must make a new decision, record it in an ADR in the same PR and name it in the PR description. Do not write review rules.
- Reviewer (CodeRabbit): it reviews with no session context against the design sources. Its configuration is `.coderabbit.yaml`. When you answer a review finding, do one of these: fix the code; fix the design source in the same PR; add a check to `scripts/check-repo-rules.sh` or `scripts/verify-contract.sh`; or reject the finding and cite the design source section. Learnings only calibrate CodeRabbit. Do not rely on them for design facts.
- PR descriptions state what changed, which design sections it follows, and the exact verification commands and results. Never label a step verified without a run.
