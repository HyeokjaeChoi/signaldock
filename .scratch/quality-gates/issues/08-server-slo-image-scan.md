# 08. Server ingest load SLO and container image scan

Status: resolved
Type: task

## Scope and completion criteria

Spec gates B3, B4. ADR-0049 decisions 2, 5, 7, 10; ADR-0013; ADR-0045 item 6.

1. B3, nightly job `ingest-slo` in `nightly.yml`: `perf/ci-slo.sh` starts Compose on the runner (`perf/compose.perf.yml`, empty database), then k6 (`perf/ci-slo.js`) sends 20 req/s x 20 events for 120 s. Fails on any request that is not 200 with all events accepted, on a dropped iteration, or when p95 > 2x `p95Ms` of `perf/ci-slo-baseline.json`. The rule is in `perf/ci_slo.py` (decision 7: p95 rules stay in Python). No retry.
2. The baseline is measured in real CI runs of the same runner class (`ubuntu-latest`) and committed with the run IDs (decision 2). The rule is "fail above 2x".
3. B4, PR workflow `image-scan.yml`: builds the server and Dashboard images and runs Trivy (same pinned image and flags as `demo-deploy.yml`: `--ignore-unfixed --severity CRITICAL,HIGH --exit-code 1`), only when a Dockerfile or a dependency file of the two images changes (`paths:`).
4. Proof: each gate fails on a bad sample and passes on the real input; run IDs in an ADR-0049 amendment.
5. `AGENTS.md` "Enforced rules" rows for both gates; `docs/design-index.md` entries.

Execution: Done (merged in PR #296, 2026-11-16 UTC)
