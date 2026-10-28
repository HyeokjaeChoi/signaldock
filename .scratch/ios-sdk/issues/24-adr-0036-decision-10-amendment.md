# 24. ADR-0036 decision 10: dropped-events card now follows the platform filter

Status: ready-for-agent
Type: task
Blocked by: None

## Scope and completion criteria

Minor finding from the PR #249 Claude review, recorded under AGENTS.md rule 7.

PR #249 (ticket 03) sends the platform filter to the dropped-events card and adds a platform column. ADR-0036 decision 10 still says the card lists SDK version, reason and count, and follows the period, not the event filters. The ADR-0036 Amendment (2026-10-27) supersedes only decision 9.

1. Extend the ADR-0036 Amendment to cover decision 10: the card shows platform, version, reason and count (ADR-0046 decision 6), and follows the period and the platform filter only.
2. Check `docs/design-index.md` and the Dashboard docs for the same statement.

Verification:
- `bash scripts/check-repo-rules.sh; echo "exit=$?"` is 0.
