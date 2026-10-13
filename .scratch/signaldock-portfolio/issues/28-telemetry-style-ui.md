# 28. Telemetry-style redesign of the Dashboard and the benchmark report

Status: claimed
Type: task
Blocked by: 27
Execution: Implemented on branch impl/28-telemetry-style-ui, PR pending

## Scope and completion criteria

Redesign the Dashboard (`dashboard/`) and the benchmark report page (`perf/report/`) so that they read like the dashboards of major telemetry vendors (Datadog, Sentry, Grafana): clear hierarchy, scannable key numbers, consistent charts, and fast drill-down from summary to one event or one run.

Method:
- Reference research first: collect patterns from real vendor dashboards with Mobbin (through computer use or the agent-browser skill; the user signs in, the agent never enters credentials) and Lazyweb design evidence. Record the chosen patterns and their sources before building.
- Apply design skills during the build: Emil Kowalski design engineering (`emil-design-eng`) and `impeccable` (critique, polish, accessibility, states), and others that fit.
- Keep the i18n approach from issue 27 for every new string.

Constraints:
- Spec section 12 says the Dashboard verifies SDK results and that Dashboard UX selection questions stop. This ticket changes that scope, so record the decision (spec Q entry) and update section 12 and `docs/design-index.md` before the build.
- Keep the query API, polling, retry, and contract-error behavior (ADR-0007, spec Q107 to Q109). This is a presentation change.
- Keep the existing pins unless a change is needed (TOOLCHAIN.md); a new dependency needs a reason.
- Accessibility basics: contrast, keyboard use, readable charts without color alone.

Decided questions (do not reopen):
1. Vendor patterns per view: decided by Q140 (see `issue-28-design-references.md`).
2. Theme and tokens: decided by Q140 (dark default, light switch, shared design tokens).
3. Benchmark report scope: decided by Q139 and Q140 (presentation only; no A/B run compare).

Done when both pages follow the recorded design decisions, existing tests pass, before and after screenshots in both languages are in the evidence folder, and a design critique (for example with `impeccable`) finds no major issue.

## Comments

2026-10-13: Created at the user's request (GitHub mirror: #62). Ordered after issue 27 so that the redesign uses the message catalog from the start.

2026-10-13: Q139 decides the scope: presentation layer only (tokens, light and dark theme, layout, chart style, status states, summary cards); views and behavior stay. Issue 27 is done (PR #64), so this issue is unblocked. Reference research (Lazyweb reports on the current Dashboard and benchmark report screenshots) started.

2026-10-13: Reference research done: `issue-28-design-references.md` (Lazyweb reports on both pages, Mobbin captures of Sentry and Vercel kept outside the repo). Q140 fixes the pattern set. Ready for implementation.
