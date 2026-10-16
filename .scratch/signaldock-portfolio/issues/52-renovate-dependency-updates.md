# 52. Renovate for dependency updates

Status: claimed
Type: task
Blocked by: None
Execution: Branch `ci/renovate`. The decision is spec Q160, ADR-0031.

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

- Gap: the Renovate app is not installed. The first real run log must show the catalog and Google Maven lookups.
