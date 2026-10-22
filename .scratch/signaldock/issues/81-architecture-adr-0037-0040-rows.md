# 81. Add ADR-0037 to ADR-0040 to the architecture decisions table

Status: claimed
Type: task
Blocked by: None
Execution: In an open PR (2026-10-22 UTC)

## Scope and completion criteria

Issues 77 and 80 reported that `architecture.md` "Decisions and tradeoffs" has no rows for ADR-0037 to ADR-0040. It is a design source, so the docs refresh did not change it. The user decided on 2026-10-22 UTC to add the rows.

1. Four rows, one per ADR, in the existing "Choice | Cost | Source" format. Each cost comes from the ADR's own Limits, Consequences, or Gaps section; no new decision.
2. No other line of `architecture.md` changes. No new ADR.

Verification:
- `scripts/check-repo-rules.sh`; each linked ADR exists; `coderabbit review --base main --deep --agent`.
