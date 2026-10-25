# 01. ADRs for the cloud demo and doc updates

Status: claimed
Type: task
Blocked by: None

## Scope and completion criteria

Record decisions D1 to D12 of `.scratch/cloud-demo/spec.md` before any code (AGENTS.md rule 2). Docs only.

1. New ADR(s) from 0043. One ADR replaces the ADR-0028 Decision table row by row: per-IP limit (D2), access log (D11), key policy (D11, accepted risk), TTL (D3), Dashboard login (D1). Each row names what covers it and the risk that stays. Hosting and deploy (D5 to D10) and hardening (D12) can be separate ADRs if one ADR gets too long. Each ADR has the rejected alternatives and "Revisit when" from spec sections 2 and 8.
2. ADR-0028 gets a dated `## Amendment` that points to the new ADR. Its decision text does not change (`docs/agents/doc-audit.md`).
3. `docs/engineering/threat-model.md`: public exposure, the nginx controls, metadata server, the accepted key risks.
4. `SECURITY.md`, `README.md` and `README.ko.md` limitations: the live demo, its end date, what readers can do. The two READMEs stay in step.
5. `docs/design-index.md`: the new ADRs; a section for `infra/`; the rejected alternatives.
6. `architecture.md` decisions table rows for the new ADRs.

Verification:
- `bash scripts/check-repo-rules.sh` (real exit code).
- `scripts/verify-contract.sh` is skipped by the hook for docs-only changes; state that in the PR.
