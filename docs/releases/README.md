# Releasing

A pushed tag `vX.Y.Z` builds the Maven ZIP and creates the GitHub Release ([`release.yml`](../../.github/workflows/release.yml), [ADR-0024](../adr/0024-release-workflow-and-dependency-updates.md)). It never replaces an existing release. `v0.1.0` was published by hand and is not affected.

## Cut a release

1. Change `sdkVersion` in `sdk/sdk-android/build.gradle.kts` to `X.Y.Z`. Update the version in the README install steps, the integration guide, and `compatibility/consumer-android` if they name it.
2. Write the release notes in `docs/releases/vX.Y.Z.md` (format of [v0.1.0.md](v0.1.0.md)). They are required: the workflow fails without them.
3. In `CHANGELOG.md`, rename `[Unreleased]` to `[X.Y.Z] - date` and add a new empty `[Unreleased]`.
4. Open a PR and merge it to `main`.
5. Check locally: `scripts/release.sh X.Y.Z` builds `dist/` (ZIP and `.sha256`).
6. Tag the merge commit and push the tag:

   ```sh
   git tag vX.Y.Z <merge-commit>
   git push origin vX.Y.Z
   ```

The workflow fails, before it builds, when the tag is not `vMAJOR.MINOR.PATCH`, differs from `sdkVersion`, is not on `main`, has no notes file, or already has a release. If a check fails, fix the cause, delete the tag (`git push --delete origin vX.Y.Z`), and tag again. A failed `publish` job leaves no release, so you can re-run it.

## Dependency updates

Renovate proposes weekly updates ([`renovate.json5`](../../.github/renovate.json5), [ADR-0031](../adr/0031-renovate-dependency-updates.md)). It reads the shared catalog `gradle/libs.versions.toml`.

- A PR from the group `sdk-consumer-facing` (label `sdk-consumer-facing`) changes what a consumer resolves from the published POM. Before you merge it, add a line to `CHANGELOG.md` under `[Unreleased]`.
- Renovate cannot read the pin reasons in the catalog comments (for example OkHttp 5.4.0, ADR-0006). If CI fails for such a pin, close the PR or hold the version in the catalog.
- A new major of a runtime image (JDK, Node, PostgreSQL) or of `@types/node` is never proposed. Change it by hand in its own PR (`TOOLCHAIN.md`).
