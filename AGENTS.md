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
