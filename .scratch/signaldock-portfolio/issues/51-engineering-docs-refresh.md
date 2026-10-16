# 51. Refresh the engineering documents

Status: claimed
Type: task
Blocked by: None
Execution: Branch `docs/engineering-refresh`. Documents only, no product code, no new ADR.

## Scope and completion criteria

The documents in `docs/engineering/` were written before PRs #104 to #106, #108 to #113, and #123. Update them so every claim matches `main`.

1. Each closed Gap cites a test or a run. A Gap that is still true stays.
2. A feature left out on purpose (ADR-0028) is "Excluded on purpose", with the risk kept in the text.
3. The "Gaps found" summary, the threat model residual risks, and the README index are updated.

Verification: `scripts/check-repo-rules.sh`; `coderabbit review --base main --deep --agent`.
