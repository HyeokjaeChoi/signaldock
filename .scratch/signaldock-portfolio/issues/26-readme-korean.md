# 26. README Korean version

Status: ready-for-agent
Type: task
Blocked by: None
Execution: Implemented on branch impl/26-readme-korean, PR pending

## Scope and completion criteria

Add `README.ko.md`, a Korean version of `README.md`, for Korean readers of the portfolio. Each file links to the other at the top.

Before the translation, bring `README.md` up to the final state: issues 01 to 25 are done, so headings and columns that still say "Planned" or "not yet verified" must state what was built and link the recorded evidence (issues, results, ADRs), and keep what was not verified. The Korean file follows the updated English file section by section. Technical terms, identifiers, commands, paths, and code stay in their original form. Links point to the same targets. `docs/reproduce.md` and the design sources stay English only; the Korean README links to them.

Done when both READMEs match in sections and links, every relative link resolves, and a check stops the two files from drifting apart in structure.

## Comments

2026-10-13: Created at the user's request after issue 25 merged. GitHub mirror: #58.

2026-10-13: Implemented on branch `impl/26-readme-korean`. `README.md` no longer says "Planned" or "not yet verified": the evidence table, headings, versions, and performance sections link the recorded results (issues 06, 20 to 24, `perf/results/`, `perf/sdk-results/summary.md`, ADR-0009, ADR-0013, ADR-0014) and keep the limits. `README.ko.md` follows it section by section (8 `##`, 6 `###`), with the same relative link targets. `scripts/check-repo-rules.sh` fails when the heading counts per level or the relative link targets differ, or a mutual link is missing (tested: a changed link, a changed heading level, and a missing back-link each fail with exit 1; restored files pass). All 45 relative link targets in each file exist.
