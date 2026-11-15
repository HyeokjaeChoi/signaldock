# 05. Nightly workflow, E2E delivery gate, run summaries

Status: resolved
Type: task

## Scope and completion criteria

Spec gates Q1, Q2. ADR-0049 decisions 1, 2, 5, 10; ADR-0010; ADR-0023.

1. `.github/workflows/nightly.yml`: `schedule` (18:00 UTC) and `workflow_dispatch` only; top-level `permissions: contents: read`; every `uses:` pinned by SHA.
2. Job `e2e-delivery` (Q1): Compose stack plus an API 34 x86_64 emulator on `ubuntu-latest` (KVM and runner setup of `device-test`), then `scripts/e2e-delivery.sh 1 2 3 4 5`. Fails when a case fails. No retry. `E2E_AVD` and `E2E_RESULT_JSON` are env settings; local use is unchanged.
3. Q2: each nightly job writes a JSON summary with `scripts/nightly-summary.py` and uploads it as `nightly-summary-<job>`. Format in `docs/engineering/nightly-summary.md`.
4. `scripts/check-repo-rules.sh`: `nightly.yml` triggers only on `schedule` and `workflow_dispatch`; every job runs `nightly-summary.py`. Proved on samples (a `push` trigger, a job without the step).
5. Tickets 06, 07, 08, 11 add their jobs after this PR merges. This ticket owns `nightly.yml`, the `AGENTS.md` rows, and `docs/design-index.md` in wave 2.
6. Proof before merge: a temporary `push` trigger on the branch; run IDs and job times in the PR and in an ADR-0049 amendment; the trigger removed.

Execution: Done (PR #288, merged as 82f968c). Proof runs 43683069360 and 43691646307 (job 12 min 10 s and 11 min 25 s); see the ADR-0049 amendment of ticket 05.
