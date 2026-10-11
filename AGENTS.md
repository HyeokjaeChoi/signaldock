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

## Code review

Review rules live in `docs/review/`, one file per area:

| Area | File |
| --- | --- |
| Repository-wide, "Do not suggest" | `docs/review/repo.md` |
| `sdk/` | `docs/review/sdk.md` |
| `server/` | `docs/review/server.md` |
| Contract and codegen | `docs/review/contract.md` |
| Scripts, toolchain, verification | `docs/review/verification.md` |

Before you edit or review files in an area, read its file. CodeRabbit loads them through `.coderabbit.yaml`, which also owns each file's path scope.

### Adding review knowledge

When a review finding settles a rule, record it in the first place that fits:

1. A machine can check it: add the check to `scripts/check-repo-rules.sh` or `scripts/verify-contract.sh`. Do not also write it as prose.
2. It is a judgment call for one area: add one bullet to that area's file in `docs/review/`. End the bullet with the source PR, for example `(PR #9)`.
3. It is a design decision that crosses areas: write an ADR in `docs/adr/`.

Do not add review rules to this file. If an area file grows past about 60 lines, split it by sub-area, add a row to the table, and map it in `.coderabbit.yaml`.

Record a settled rule in `docs/review/` in the same PR. CodeRabbit learnings wait 30 days as pending. `.github/workflows/learnings-sync.yml` opens a PR for any learning that a merged PR did not record. After that PR merges, reject the learning in the Learnings page.
