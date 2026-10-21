# Releasing

A pushed tag `vX.Y.Z` builds the Maven ZIP and creates the GitHub Release ([`release.yml`](../../.github/workflows/release.yml), [ADR-0024](../adr/0024-release-workflow-and-dependency-updates.md)). It never replaces an existing release. `v0.1.0` was published by hand and is not affected.

## Cut a release

1. Change `sdkVersion` in `sdk/sdk-android/build.gradle.kts` to `X.Y.Z`. Update the version in the README install steps, the integration guide, and `compatibility/consumer-android` if they name it. Change `SDK_VERSION` in `sdk/sdk-android/src/main/kotlin/local/signaldock/internal/transport/Versions.kt` to the same value: `scripts/check-repo-rules.sh` fails when the two differ.
2. Run the `update-internal-docs` skill ([`SKILL.md`](../../.claude/skills/update-internal-docs/SKILL.md)). Merge its PR before you tag.
3. Write the release notes in `docs/releases/vX.Y.Z.md` (format of [v0.1.0.md](v0.1.0.md)). They are required: the workflow fails without them.
4. In `CHANGELOG.md`, rename `[Unreleased]` to `[X.Y.Z] - date` and add a new empty `[Unreleased]`.
5. Open a PR and merge it to `main`.
6. Check locally: `scripts/release.sh X.Y.Z` builds `dist/` (ZIP and `.sha256`).
7. Tag the merge commit and push the tag:

   ```sh
   git tag vX.Y.Z <merge-commit>
   git push origin vX.Y.Z
   ```

The workflow fails, before it builds, when the tag is not `vMAJOR.MINOR.PATCH`, differs from `sdkVersion`, is not on `main`, has no notes file, or already has a release. If a check fails, fix the cause, delete the tag (`git push --delete origin vX.Y.Z`), and tag again. A failed `publish` job leaves no release, so you can re-run it.

## Provenance attestation

The `publish` job signs a build provenance attestation for the ZIP before it creates the release ([ADR-0039](../adr/0039-release-provenance-attestation.md)). It needs no repository setting. The job has the extra permissions `id-token: write` and `attestations: write`.

After the workflow succeeds, check the attestation of the released ZIP. Download the ZIP, then run:

```sh
gh attestation verify signaldock-sdk-android-X.Y.Z-maven.zip --repo HyeokjaeChoi/signaldock
# stricter: also pin the workflow file
gh attestation verify signaldock-sdk-android-X.Y.Z-maven.zip --repo HyeokjaeChoi/signaldock \
  --signer-workflow HyeokjaeChoi/signaldock/.github/workflows/release.yml
```

Use GitHub CLI 2.102.0 or newer. Versions 2.51.0 to 2.101.0 match `--signer-workflow` as a prefix (GHSA-wjmr-j3rp-mh2g), and older versions have other attestation check flaws (GHSA-mm27-mwq9-fr5g). Both commands must print `Verification succeeded!`. Record the result in the PR or issue of the release. `v0.2.0` and earlier have no attestation. `v0.3.0` is the first release with an attestation. Its first run passed: release workflow run 39634744975 produced the attestation, and the stricter command passed on the released ZIP on 2026-10-21 UTC ([issue 78](../../.scratch/signaldock/issues/78-release-v0-3-0.md)).

## Dependency updates

Renovate proposes weekly updates ([`renovate.json5`](../../.github/renovate.json5), [ADR-0031](../adr/0031-renovate-dependency-updates.md)). It reads the shared catalog `gradle/libs.versions.toml`.

- A PR from the group `sdk-consumer-facing` (label `sdk-consumer-facing`) changes what a consumer resolves from the published POM. Before you merge it, add a line to `CHANGELOG.md` under `[Unreleased]`.
- Renovate cannot read the pin reasons in the catalog comments. Documented pins have a rule in `renovate.json5` (ADR-0031: OkHttp below 5.5.0). If CI fails for another pin, close the PR, hold the version in the catalog, and add a rule.
- A Renovate Gradle PR fails the Gradle CI jobs with "Dependency verification failed", because Renovate does not update `gradle/verification-metadata.xml` in this repo ([ADR-0038](../adr/0038-gradle-dependency-verification.md)). Check out the PR branch, run `scripts/update-gradle-verification.sh`, check that the new entries match the update, and push the changed files once. The script runs `dependencyGuardBaseline`, so it also rewrites `sdk/sdk-android/dependencies/` when the SDK release classpath changed: commit that change and add the `CHANGELOG.md` line (see "Change the SDK dependency tree").
- A new major of a runtime image (JDK, Node, nginx, PostgreSQL) or of `@types/node` is never proposed. Change it by hand in its own PR (`TOOLCHAIN.md`).

## Change the SDK dependency tree

`scripts/verify-contract.sh` fails when the release runtime or compile classpath of `:sdk-android` differs from `sdk/sdk-android/dependencies/` ([ADR-0033](../adr/0033-sdk-dependency-baseline.md)). A dependency bump, a Renovate PR, or a transitive change can cause it. To accept the change:

```sh
sdk/gradlew -p sdk :sdk-android:dependencyGuardBaseline
```

Commit the changed baseline files. A baseline change is a change a consumer can see, so add a line to `CHANGELOG.md` in the same PR.
