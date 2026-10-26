# 16. Docs audit after the cloud demo (PRs 207 to 223)

Status: resolved
Execution: Done (PR #234, 2026-10-26 UTC)
Type: task
Blocked by: None

## Scope and completion criteria

Audit of the Markdown docs against the code after PRs 207 to 223, as `docs/agents/doc-audit.md` says. Source of the facts: issues 05, 07, and 14 (first cloud setup, 2026-10-25 UTC).

### Fixed

- `README.md` and `README.ko.md`: "Planned live demo" is now "Live demo" (deployed 2026-10-25 UTC, tag `demo-v0.1.1`); "Local only" names the demo as the one exception; three screenshots of the live demo (2026-10-26 UTC); CI bullet names `claude-review` and `demo-deploy`.
- `SECURITY.md` lines 3 and 50: the demo is deployed.
- `architecture.md` three cloud demo rows: "planned, not verified" is now a deployed status with the not-run checks.
- `docs/design-index.md`: heading "Cloud demo" is "deployed 2026-10-25 UTC". The paragraph already named runbook sections 18 to 20 correctly.
- `.scratch/cloud-demo/spec.md` line 3: status line follows the first cloud run.
- `docs/engineering/threat-model.md` section 9: deployed, what ran and what did not, "Control" column, status stamp.
- `docs/engineering/README.md`: later-PR list.
- `docs/runbook-demo.md`: line 5 (first setup ran), section 9 (four timers, report timer), section 12 (`--prune origin`), section 19 (alert policy disabled while the VM is stopped).
- `TOOLCHAIN.md`: Terraform, provider, and VM image row.
- `CHANGELOG.md` `[Unreleased]`: cloud demo infrastructure, brotli-libs fix, Flyway connect retries.
- Issue 20: the README video, not made yet (needs the owner). Issues 17 to 19 are used by PR #233.

### Kept

- Runbook sections 7, 11 (step 3 and the GHCR public-package check), and 15: not changed here, because PR #231 fixed them (issues 12, 13, 15).
- ADR texts: unchanged (doc-audit "Do not change").
- The `Gap:` on the metadata rule in the threat model (IPv6, reboot, compose bridges): still open.

Verification: `scripts/check-repo-rules.sh`; `test -e` on every new relative link.
