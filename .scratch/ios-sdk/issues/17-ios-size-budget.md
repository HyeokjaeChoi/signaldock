# 17. iOS size budget gate

Status: ready-for-agent
Type: task
Blocked by: 16

## Scope and completion criteria

Spec D13 item 10, D15. ADR-0023.

1. A CI job fails when the SDK adds more than a limit to the consumer Release build. The limit comes from ticket 16 with headroom, and the reason for the number is written down.
2. `AGENTS.md` table row.

Verification:
- CI run link; a planted size increase fails the job (output).
