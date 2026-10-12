# 18. Contract: document the 720-bucket limit as PERIOD_TOO_LONG

Status: ready-for-agent
Type: task
Blocked by: None
Execution: Done (merged in PR #32, 2026-10-12)

## Scope and completion criteria

From the CodeRabbit Minor finding on PR #32 (issue 14), `openapi/contract-v1.yaml` near the `countReceivedEventsByTime` operation. The server rejects more than 720 buckets with 400 `PERIOD_TOO_LONG`, but the operation description does not say so. State it in the description, regenerate `generated/`, and update the baseline on purpose (ADR-0002 amendment) with the oasdiff output.

Done when `scripts/verify-contract.sh` passes with the new description.

## Comments

2026-10-12: Recorded from the PR #32 review (Minor, merge not held, AGENTS.md "Review workflow" 5).

2026-10-12: Done.

- `openapi/contract-v1.yaml`: the `countReceivedEventsByTime` description now says a request that produces more than 720 buckets returns 400 `PERIOD_TOO_LONG`.
- `scripts/codegen.sh` ran with JAVA_HOME=`.tools/jdk-21.0.12.1+1`; `generated/` did not change (descriptions are not in the models).
- `fixtures/contract/contract-v1.baseline.yaml` updated on purpose (ADR-0002 amendment), copied from the new contract.
- Base = `git show main:fixtures/contract/contract-v1.baseline.yaml` (main has no query endpoints yet, so the by-name, by-time, events and events/{eventId} endpoints show as added):
  - `.tools/oasdiff breaking <base> openapi/contract-v1.yaml` -> "No breaking changes to report, but the specs are different." (exit 0)
  - `.tools/oasdiff changelog <base> openapi/contract-v1.yaml` -> `4 changes: 0 error, 0 warning, 4 info`, all `endpoint-added`: GET /v1/event-counts/by-name, GET /v1/event-counts/by-time, GET /v1/events, GET /v1/events/{eventId}.
- Base = the branch baseline before this update (isolates this change): `oasdiff breaking` -> no breaking changes; `oasdiff changelog` -> "No changes to report, but the specs are different."; `oasdiff diff` -> only `GET /v1/event-counts/by-time` `description` changed (new sentence "a request that produces more than 720 buckets returns 400 PERIOD_TOO_LONG").
- `scripts/verify-contract.sh` -> `ALL CONTRACT CHECKS PASSED`.

2026-10-12: Record reconciled. Merged into main in PR #32 on 2026-10-12. Earlier branch and push notes above describe the state before that merge.
