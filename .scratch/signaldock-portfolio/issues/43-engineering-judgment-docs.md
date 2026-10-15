# 43. Engineering judgment documents

Status: claimed
Type: task
Blocked by: None
Execution: Implemented on branch docs/engineering-judgment, PR pending

## Scope and completion criteria

Documents only, no code change. Under `docs/engineering/`, in simple English (ASD-STE100 style). Every factual claim cites a file, an ADR, a spec Q, a test, or a result path. A gap is marked as a gap, not invented.

1. `design-review.md`: Google design doc outline, alternatives from ADRs and the spec only, and an analysis of what breaks first at 10x and 100x from the numbers in `perf/`.
2. `failure-modes.md`: FMEA table with evidence per row.
3. `postmortems/2026-10-14-pr95-closed-unmerged.md` and `postmortems/2026-10-14-review-findings-missed.md`: blameless format.
4. `threat-model.md`: OWASP process, STRIDE per element, MASVS view, privacy.
5. `docs/engineering/README.md`, one line in `README.md` and `README.ko.md`, an entry in `docs/design-index.md`, and `CHANGELOG.md`.

Verification:
- `scripts/check-repo-rules.sh`; `coderabbit review --base main --deep --agent`.

## Comments
