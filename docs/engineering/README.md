# Engineering judgment

These documents show how I reason about the design, its failures, and its risks. Each claim cites a file, an ADR, a spec Q, a test, or a result. Gaps are marked as gaps.

| Document | Question it answers |
| --- | --- |
| [design-review.md](design-review.md) | What is the design, what did we reject, and what breaks first at 10x and 100x? |
| [failure-modes.md](failure-modes.md) | For each failure, what happens, what limits it, and which test proves it? |
| [postmortems/2026-10-14-pr95-closed-unmerged.md](postmortems/2026-10-14-pr95-closed-unmerged.md) | Why did a failed merge call close PR #95? |
| [postmortems/2026-10-14-review-findings-missed.md](postmortems/2026-10-14-review-findings-missed.md) | Why did two review findings reach `main` unseen? |
| [threat-model.md](threat-model.md) | What can an attacker do, what stops them, and what is open? |

The scale section of the design review is analysis. Only the numbers in `perf/results/` and `perf/sdk-results/` were measured.

## Refresh status

Refreshed 2026-10-20 UTC against commit `a12d05b`, before SDK v0.3.0, in PR #195 ([issue 77](../../.scratch/signaldock/issues/77-pre-release-0.3.0-docs-refresh.md)). Later PRs that changed these documents: #197 (threat model: attestation result, [issue 78](../../.scratch/signaldock/issues/78-release-v0-3-0.md)), #198 (this stamp, [issue 80](../../.scratch/signaldock/issues/80-stale-cleanup-after-v0.3.0.md)), #204 (threat model), #207 (threat model section 9), and #234 (threat model section 9 status, [issue 16](../../.scratch/cloud-demo/issues/16-docs-audit-after-cloud-demo.md)). Previous refresh: 2026-10-17 UTC against commit `cbb6210` (tag `v0.2.0`), in PR #168 ([issue 63](../../.scratch/signaldock/issues/63-refresh-stale-docs.md)). The PR lists come from `git log --oneline -- docs/engineering/`. Earlier refresh: 2026-10-15 UTC (issue 51), after PRs #104 to #106, #108 to #113, and #123. Later PRs that changed these documents: #144 to #148, #158 to #161 (threat model), #159 and #160 (design review), #161 (failure modes and design review), #173 (threat model), #174 (threat model, failure modes, design review), #175 (design review), #180 and #181 (threat model), #189 (threat model). A Gap that a test now covers cites that test. Where the last run is recorded only in a PR description or comment, the row keeps a `Gap:` for the missing result file. A feature that the user left out for local-only operation is "Excluded on purpose" ([ADR-0028](../adr/0028-server-production-features-excluded.md)), not a Gap. Gaps that are still true stay listed: see "Gaps found" in [failure-modes.md](failure-modes.md), "Residual risks" in [threat-model.md](threat-model.md), and the Gap lines in [design-review.md](design-review.md).
