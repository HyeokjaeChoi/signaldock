# 14. Receipt query API (part of issue 05)

Status: ready-for-agent
Type: task
Blocked by: None
Execution: Not started

## Scope and completion criteria

Add the Dashboard query API to `openapi/contract-v1.yaml` and implement it in a new server module `:query` (ADR-0005, section "Server: layered Gradle modules"). Spec section 6 "Dashboard": received-event list and details, counts by type and time, sort, period filter, aggregation by receivedAt, and occurredAt in details. ADR-0002 Q107: the contract version header is required. Follow `.scratch/signaldock-portfolio/dashboard-verification-defaults.md` and the architecture section "DB time budgets and queries" (no aggregate tables or counters).

Done when server tests, `scripts/verify-contract.sh`, and the oasdiff record (ADR-0002 amendment "intentional baseline update") are recorded here.

## Comments

2026-10-12: Split from issue 05.
