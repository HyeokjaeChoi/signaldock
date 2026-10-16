# 52. Renovate for dependency updates

Status: resolved
Type: task
Blocked by: None
Execution: Done (merged in PR #144, 2026-10-16 16:31:14 UTC)

## Scope and completion criteria

Spec Q160. Dependabot cannot see the shared Gradle catalog `gradle/libs.versions.toml`. Renovate can.

1. `.github/renovate.json5` keeps the ADR-0024 policy: weekly, small PR limit, Dependency Dashboard, groups by blast radius, Google Maven for AndroidX and AGP, digest and SHA pinning, no major updates for runtime images and `@types/node`.
2. `.github/dependabot.yml` is deleted. Dependabot security alerts stay (repository setting).
3. `pr-description.yml` skips `renovate[bot]` PRs.
4. ADR-0031 supersedes ADR-0024 items 5 and 6 and the amendment; `design-index.md`, `docs/releases/README.md`, `TOOLCHAIN.md`, and `threat-model.md` are updated.

Verification:
- `npx --yes --package renovate -- renovate-config-validator .github/renovate.json5`
- Local dry run: `renovate --platform=local --dry-run=lookup` with the config as `RENOVATE_CONFIG_FILE`. No token.

## Comments

- 2026-10-16 UTC: PR #144 merged (config, Dependabot version updates removed, ADR-0031).
- 2026-10-16 UTC: Renovate GitHub App installed, limited to the `signaldock` repository only (it was "All repositories"). Mend onboarding: "Renovate Only" (Community, free), mode "Scan and Alert" (Interactive).
- 2026-10-16 UTC: first Renovate output is PR #149 "Update dependency org.postgresql:postgresql to v42.7.12 [SECURITY]", labels `dependencies` and `security`. For Gradle only the OSV check (`osvVulnerabilityAlerts`) applies, because GitHub does not parse the Gradle builds (ADR-0031 item 9).
- 2026-10-16 UTC: Dependency Dashboard issue #150 exists (created 17:03:03Z; `gh issue list --author app/renovate --state all`). It did not exist right after the merge.
- ADR-0031 "Gaps" and `docs/engineering/threat-model.md` now state the install.
- 2026-10-16 UTC: Google Maven lookups work: Dependency Dashboard #150 (2026-10-16 UTC) lists updates found for `androidx.activity:activity-compose` (1.13.0), `androidx.lifecycle` (2.11.0), and the AGP plugins `com.android.library` and `com.android.application` (9.4.1).
