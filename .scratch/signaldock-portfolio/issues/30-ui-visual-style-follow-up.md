# 30. Visual style follow-up for the Dashboard and the benchmark report

Status: needs-triage
Type: task
Blocked by: 29
Execution: Not started

## Scope and completion criteria

The design reviews of issue 28 (2026-10-13) disagreed on several visual style choices, so issue 28 kept them unchanged. Each needs a user decision (spec Q entry) before a build. Presentation layer only (Q139).

Open questions:
1. Shape: keep 12 px card radius and 6 px nested radius, or move to 8 px and 4 px (pill only for badges)?
2. Structure: keep every area as a card, or make the toolbar a flat bar with a bottom border and reduce cards inside cards?
3. Palette: keep the current dark and light tokens, or give the product its own accent and neutral scale?
4. KPI cards: keep the current size, or make them smaller and give every card a sub line?
5. Run id in the report: keep the bordered button, or use the same link style as the Dashboard?
6. Timestamps: keep the sans-serif font, or use the monospace font with the date dimmed?
7. Mobile filters: keep all filters open at 390 px, or put the three exact filters behind a "More filters" disclosure?

Completion criteria:
- The answers are recorded as spec Q entries and in ADR-0016.
- The changes follow the answers, with screenshots before and after (en and ko, dark and light, 1440, 1024, and 390 with touch emulation).
- The checks of issue 29 pass.
