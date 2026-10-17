# 48. Record server production features excluded for local-only operation

Status: resolved
Type: task
Blocked by: None
Execution: Done (merged in PR #108, 2026-10-15 UTC)

## Scope and completion criteria

Spec Q157: the server is tested only locally on OrbStack and is not operated on the public internet. Record the server production features that are left out on purpose. The decision is in ADR-0028.

1. Spec Q157 states the decision and the list.
2. ADR-0028 gives the context, the decision with the existing source of each item, the consequences, and a "Revisit when" trigger.
3. `docs/design-index.md` lists ADR-0028 and Q157 in the `server/` section.
4. `docs/engineering/*` does not change.

Verification:
- `scripts/check-repo-rules.sh` (runs in the pre-commit hook)
