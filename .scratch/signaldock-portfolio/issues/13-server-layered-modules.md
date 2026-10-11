# 13. Server layered Gradle modules

Status: ready-for-agent
Type: task
Blocked by: 11
Execution: Not started

## Scope and completion criteria

Split `server/` into `:app`, `:ingestion`, and `:persistence` as ADR-0005, section "Server: layered Gradle modules", defines. `:ingestion` must not compile against Ktor, jOOQ, Flyway, or the generated DTOs. No behavior change.

Done when server tests with `verifyJooq` and `scripts/verify-contract.sh` pass, with commands and results recorded here.

## Comments

2026-10-11: Created with ADR-0005.
