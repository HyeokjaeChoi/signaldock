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

2026-10-13: Implemented on branch impl/28-telemetry-style-ui (PR pending). Decisions are in ADR-0016 (tokens in `web-shared/theme/`, theme key `signaldock.theme`, breakpoints 1100 px and 720 px, KPI derivations). Checks run: `cd dashboard && npm ci && npm run typecheck && npm test && npm run build && npm run check-bundle-key && npm run contract-check` (typecheck clean, 103 tests pass, build and both checks ok); `cd perf/report && npm ci && npm run typecheck && npm run build` (clean); `scripts/check-repo-rules.sh` (ok). New tests: KPI derivations, theme module and persistence, token contrast (AA, both themes), theme switch and KPI cards render. Evidence: `evidence/28-before-*.png` and `evidence/28-after-*.png` for both pages in English and Korean, dark and light (plus a 390 px mobile view of each page), captured with headless Chrome; the Dashboard ran from Compose under the project `signaldock-ui28` with seeded events (stopped with `docker compose down`, volumes kept). `impeccable` detector: no findings. `impeccable` critique (design review as an isolated sub-agent, score 29/40): major findings were bar charts told apart by color only (fixed: hatch and dot textures in `BAR_FILL`, same fills in legends) and wide report tables clipped without a scroll cue (fixed: edge shadows on `.table-wrap`; the table already scrolled). Minor findings fixed: amber `pilot` badge made neutral, `scroll-padding-top` under the sticky toolbar. Minor findings not done: Copy buttons per row, repeated Live badges, en-GB date format (kept from issue 27), no `aria-live` on the state badge (it would announce every poll), no per-name trend (not derivable from the by-time counts).

