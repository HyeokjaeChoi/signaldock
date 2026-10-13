# 30. Visual style follow-up for the Dashboard and the benchmark report

Status: claimed
Type: task
Blocked by: 29
Execution: Implemented on branch impl/30-ui-visual-style, PR pending

## Scope and completion criteria

The design reviews of issue 28 (2026-10-13) disagreed on several visual style choices, so issue 28 kept them unchanged. Each needs a user decision (spec Q entry) before a build. Presentation layer only (Q139).

Decided questions (do not reopen), spec Q141 to Q147 (2026-10-14):
1. Shape: keep 12 px and 6 px radius (Q141).
2. Structure: the toolbar becomes a flat bar with a bottom border; cards inside cards lose their inner border (Q142).
3. Palette: keep the current tokens (Q143).
4. KPI cards: smaller (value 1.5 rem, less padding), and every card has a sub line from existing data (Q144).
5. Run id in the report: the Dashboard link style (Q145).
6. Timestamps: monospace, date part dimmed (Q146).
7. Mobile filters: at 720 px and below, the three exact filters sit in a `<details>` "More filters" disclosure, open when a filter is in force (Q147).

Completion criteria:
- The decisions are recorded in ADR-0016.
- The changes follow the answers, with screenshots before and after (en and ko, dark and light, 1440, 1024, and 390 with touch emulation).
- The checks of issue 29 pass.

## Comments

- 2026-10-14: The user answered the seven open questions one at a time in the session (spec Q141 to Q147): 1 a (keep radius), 2 a (flat toolbar bar), 3 a (keep tokens), 4 a (smaller KPI cards with sub lines), 5 a (run id as link), 6 a (monospace timestamps, dimmed date), 7 a ("More filters" disclosure). Status moved from `needs-triage` to `ready-for-agent`.
