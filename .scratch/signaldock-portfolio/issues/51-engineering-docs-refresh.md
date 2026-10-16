# 51. Refresh the engineering documents

Status: resolved
Type: task
Blocked by: None
Execution: Done (merged in PR #137, 2026-10-16 UTC)

## Scope and completion criteria

The documents in `docs/engineering/` were written before PRs #104 to #106, #108 to #113, and #123. Update them so every claim matches `main`.

1. Each closed Gap cites a test or a run. A Gap that is still true stays.
2. A feature left out on purpose (ADR-0028) is "Excluded on purpose", with the risk kept in the text.
3. The "Gaps found" summary, the threat model residual risks, and the README index are updated.

Verification: `scripts/check-repo-rules.sh`; `coderabbit review --base main --deep --agent`.

## Comments

- PR #137 took five CodeRabbit rounds. The fixes: run outcomes without a committed result file became test-coverage claims with a `Gap:` for the missing result file; refresh dates use UTC; two threat-model claims got sources; the deletion row follows spec 5 and ADR-0028; the clock-jump row states the exception for retry rows migrated from schema 1 (`waitMillis = null`, ADR-0025 decision 4).
