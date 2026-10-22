# 81. Add ADR-0037 to ADR-0040 to the architecture decisions table

Status: resolved
Type: task
Blocked by: None
Execution: Done (PR #200, 2026-10-22 UTC)

## Scope and completion criteria

Issues 77 and 80 reported that `architecture.md` "Decisions and tradeoffs" has no rows for ADR-0037 to ADR-0040. It is a design source, so the docs refresh did not change it. The user decided on 2026-10-22 UTC to add the rows.

1. Four rows, one per ADR, in the existing "Choice | Cost | Source" format. Each cost restates a cost the ADR itself records (ADR-0037 `Limits`; ADR-0038 Decisions 1 and 6 plus `Gaps`; ADR-0039 `Consequences`; ADR-0040 Decisions 5 and 6 plus `Result`); no new decision.
2. No other line of `architecture.md` changes. No new ADR.

Verification:
- `scripts/check-repo-rules.sh`; each linked ADR exists; `coderabbit review --base main --deep --agent`.
