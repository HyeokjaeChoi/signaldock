# 12. SDK internal packages per concern

Status: ready-for-agent
Type: task
Blocked by: 11
Execution: Not started

## Scope and completion criteria

Move `local.signaldock.internal` code into the packages that ADR-0005, section "SDK: one publication module, feature packages", lists. No behavior change. Public API stays in `local.signaldock`.

Done when SDK tests and `scripts/verify-contract.sh` pass, with commands and results recorded here.

## Comments

2026-10-11: Created with ADR-0005.
