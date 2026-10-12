# 22. Contract and DB change checks (part of issue 07)

Status: ready-for-agent
Type: task
Blocked by: None
Execution: Not started

## Scope and completion criteria

Spec section 7 rows "Contract changes" and "DB changes".

- Contract: regenerate and compile models, compare with the prior schema, and check prior-request and new-response fixture compatibility. Much of this runs in CI already (`scripts/verify-contract.sh`); confirm what runs and fill gaps.
- DB: Flyway migrations, jOOQ generation, and server queries on an empty DB and on a DB at the previous schema.

Done when each check runs from a command (CI where cheap), with results recorded here.

## Comments

2026-10-12: Split from issue 07.
