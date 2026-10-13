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
- 2026-10-14: CodeRabbit round 1 on branch impl/30-ui-visual-style (3 minor findings):
  - Fixed: the 503 share card said "No overload run" when an overload run exists without a collection rate. It now says "No overload rate" (`kpi.noRateSub`).
  - Rejected: dates in spec Q141 to Q147 and issue 29 read as future dates. The project records dates in Seoul time (spec Q78, Q79); they come from the base branch (PR #73) and are not part of this change.
  - Rejected: PR #72 merge date "UTC" label in issue 29. Same source, base branch, outside the purpose of this PR (AGENTS.md review workflow 1).
- 2026-10-14: CodeRabbit round 2 (2 minor findings), both fixed: the Query state card said "No successful query yet" while one query had already succeeded (it now shows the last success time of any query); Clear now also closes "More filters" when the draft was never applied. Round 3: no findings.
