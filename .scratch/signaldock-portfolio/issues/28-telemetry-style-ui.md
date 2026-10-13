# 28. Telemetry-style redesign of the Dashboard and the benchmark report

Status: needs-triage
Type: task
Blocked by: 27
Execution: Not started

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

Open questions (decide before implementation):
1. Which vendor patterns to adopt for each view: overview, event list and detail, time series, benchmark runs.
2. Light and dark theme, and design tokens shared by the two pages.
3. How much of the benchmark report changes (layout only, or new views such as run comparison).

Done when both pages follow the recorded design decisions, existing tests pass, before and after screenshots in both languages are in the evidence folder, and a design critique (for example with `impeccable`) finds no major issue.

## Comments

2026-10-13: Created at the user's request (GitHub mirror: #62). Ordered after issue 27 so that the redesign uses the message catalog from the start.
