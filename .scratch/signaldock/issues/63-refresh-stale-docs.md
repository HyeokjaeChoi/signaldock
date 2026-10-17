# 63. Refresh stale docs and add the update-internal-docs skill

Status: resolved
Type: task
Blocked by: None
Execution: Done (merged in PR #168, 2026-10-17 11:55:15 UTC)

## Scope and completion criteria

Docs drifted from the code after v0.2.0 (audit at `main` @ `cbb6210`, 2026-10-17 UTC). For descriptive docs, source code, build files, scripts, workflows, OpenAPI, migrations, and fixtures are the source of truth. If code disagrees with a design source (ADR, spec Q, design-index), report it and resolve it under AGENTS.md "Roles".

1. Fix the stale claims in README (English and Korean), `architecture.md`, `GLOSSARY.md`, `TOOLCHAIN.md`, the onepager (Markdown and HTML), `SECURITY.md`, `CONTRIBUTING.md`, the integration guide (English and Korean), `CHANGELOG.md`, `docs/releases/v0.2.0.md`, `docs/engineering/`, `docs/design-index.md`, `.coderabbit.yaml`, and `AGENTS.md`. Each fix was checked against the code first; items the code contradicts are skipped and listed in the PR.
2. Not changed on purpose: ADRs, postmortems, perf results, dated historical rows (for example the `TOOLCHAIN.md` issue #6 row), and the record of issue 62.
3. New project skill `.claude/skills/update-internal-docs/SKILL.md`: finds docs that drifted from the code and fixes them. It runs at every release and on demand. `docs/releases/README.md` "Cut a release" runs it before the release notes. `AGENTS.md` points to it.
4. No new ADR: no new design decision.

Verification:
- `scripts/check-repo-rules.sh`; the pre-push hook (`scripts/verify-contract.sh`); every added path and link exists; `coderabbit review --base main --deep --agent`.

## Comments

- 2026-10-17 UTC: PR #168 merged 11:55:15Z. Last head 3dd44d5: `scripts/check-repo-rules.sh` passed; all 17 PR checks passed. `scripts/verify-contract.sh` printed ALL CONTRACT CHECKS PASSED on an earlier head of the same PR (later commits changed Markdown only). ADR-0023 got a dated amendment for the device-test counts.
