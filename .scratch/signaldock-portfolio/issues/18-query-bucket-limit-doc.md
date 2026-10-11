# 18. Contract: document the 720-bucket limit as PERIOD_TOO_LONG

Status: ready-for-agent
Type: task
Blocked by: None
Execution: Not started

## Scope and completion criteria

From the CodeRabbit Minor finding on PR #32 (issue 14), `openapi/contract-v1.yaml` near the `countReceivedEventsByTime` operation. The server rejects more than 720 buckets with 400 `PERIOD_TOO_LONG`, but the operation description does not say so. State it in the description, regenerate `generated/`, and update the baseline on purpose (ADR-0002 amendment) with the oasdiff output.

Done when `scripts/verify-contract.sh` passes with the new description.

## Comments

2026-10-12: Recorded from the PR #32 review (Minor, merge not held, AGENTS.md "Review workflow" 5).
