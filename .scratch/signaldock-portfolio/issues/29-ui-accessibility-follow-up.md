# 29. Accessibility and layout follow-up for the Dashboard and the benchmark report

Status: claimed
Type: task
Blocked by: 28
Execution: Implemented on branch impl/29-ui-accessibility-follow-up, PR pending

## Scope and completion criteria

Issue 28 left open findings from the screenshot-based design reviews (2026-10-13, rounds 3 and 4; evidence was kept in the session scratchpad and is not committed). This ticket closes the accessibility findings and the layout findings that have one clear answer. Presentation layer only (Q139): no API, query, or data change.

Accessibility:
1. Tables have a label. Every report table and the Dashboard event table get a `<caption>` (it can be visually hidden when a heading already shows the same text) or `aria-labelledby` to the section heading.
2. Skip link. Each page has a "Skip to content" link as the first focusable element, and `<main id="main">`.
3. Row selection. When the user opens an event from the table, focus moves to the detail pane heading (`tabIndex={-1}`), and Close returns focus to the row link that opened it.
4. Touch size. On a coarse pointer, the table Copy buttons and the event-name links reach 44 px in height (they are 36 px now), without making desktop rows taller.
5. Status separators. The `" | "` separators in the per-query status line are not read aloud: draw them with CSS or `aria-hidden`.
6. Scroll and viewport. `.pane-scroll` has `overscroll-behavior: contain`; pane heights use `dvh` with a `vh` fallback; the sticky toolbar drops `backdrop-filter` (an opaque surface is enough).

Layout with one clear answer:
7. Latency charts in the report: y-axis labels do not wrap ("pool 4 · rep 1 · pilot / (pilot-1)"): give the axis enough width or shorten the label to the run id with the full text in the tooltip.
8. Report Runs table: rows are the same height as other tables (the run id control must not make rows 43 px while other tables are 30 px).

Constraints:
- Keep ADR-0015 (every visible text through `t()` in `en.ts` and `ko.ts`; the hard-coded string scan must pass) and ADR-0016 (tokens, breakpoints, controls). Record changed decisions in ADR-0016.
- No new dependency.

Verification:
- `cd dashboard && npm run typecheck && npm test && npm run build`; `cd perf/report && npm run typecheck && npm run build`; `scripts/check-repo-rules.sh`.
- Tests for items 2, 3, and 5 in the Dashboard test suite.
- Screenshots (Playwright with Chrome, en and ko, dark and light, 1440 and 390 with touch emulation) of the changed areas, with measured touch sizes and page `scrollWidth` at 390 px.

Out of scope: visual style choices (radius, palette, card structure, toolbar form, KPI size, run id chip style, monospace timestamps, mobile filter collapse). See issue 30.
