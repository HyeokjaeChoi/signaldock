# 77. Pre-release docs refresh before SDK v0.3.0

Status: resolved
Type: task
Blocked by: None
Execution: Done (merged in PR #195, 2026-10-21 15:47:59 UTC)

## Scope and completion criteria

Docs drifted from the code after v0.2.0 (audit at `main` @ `a12d05b`, 2026-10-20 UTC), before the SDK v0.3.0 release. For descriptive docs, source code, build files, scripts, workflows, OpenAPI, migrations, and fixtures are the source of truth. This work follows `.claude/skills/update-internal-docs/SKILL.md`.

1. `docs/engineering/`: add the later PRs (#173, #174, #175, #180, #181, #189) to the PR lists. Add the kill switch ([ADR-0037](../../../docs/adr/0037-server-collection-kill-switch.md)) to the DoS row of the threat model. Add the physical-phone demo install ([issue 74](74-phone-demo-install-record.md)) to the threat model.
2. Onepager (Markdown and HTML), `docs/reproduce.md`, README (English and Korean): replace "no physical device was tested" with the real state (issues 73 and 74). Add `optout` to the durability cases in `docs/reproduce.md`.
3. README (English and Korean): add Gradle dependency verification and build provenance attestation to the "CI and supply chain" row.
4. `TOOLCHAIN.md` and `gradle/libs.versions.toml`: Gradle 9.8.1 (four wrappers, PRs #117 to #120; build-verified by CI run 39615361733), vitest 5.0.5, jsdom 30.1.3, and the Node and nginx images (Renovate PR #190). JDK image digests match `server/Dockerfile`.
5. No new ADR: no new design decision.

Not changed on purpose:
- ADRs.
- SDK version strings (the release PR bumps them).
- Attestation `Gap:` lines (true until the v0.3.0 tag).
- Onepager feature lists (a content gap, not drift).
- `architecture.md` "Decisions and tradeoffs" table lacks rows for ADR-0037 to ADR-0040. It is a design source, so it was reported to the user and not changed here.

Verification:
- `scripts/check-repo-rules.sh`; `coderabbit review --base main --deep --agent`.
