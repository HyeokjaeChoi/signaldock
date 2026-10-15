# Engineering judgment

These documents show how I reason about the design, its failures, and its risks. Each claim cites a file, an ADR, a spec Q, a test, or a result. Gaps are marked as gaps.

| Document | Question it answers |
| --- | --- |
| [design-review.md](design-review.md) | What is the design, what did we reject, and what breaks first at 10x and 100x? |
| [failure-modes.md](failure-modes.md) | For each failure, what happens, what limits it, and which test proves it? |
| [postmortems/2026-10-14-pr95-closed-unmerged.md](postmortems/2026-10-14-pr95-closed-unmerged.md) | Why did a failed merge call close PR #95? |
| [postmortems/2026-10-14-review-findings-missed.md](postmortems/2026-10-14-review-findings-missed.md) | Why did two review findings reach `main` unseen? |
| [threat-model.md](threat-model.md) | What can an attacker do, what stops them, and what is open? |

The scale section of the design review is analysis. Only the numbers in `perf/results/` and `perf/sdk-results/` were measured.
