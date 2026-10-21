---
status: accepted
---

# Build provenance attestation for the release ZIP (issue 69, spec Q167)

The user approved this item on 2026-10-17 UTC (Q167). The release workflow now signs a build provenance attestation for the Maven ZIP. A consumer can check that the ZIP came from this repository's workflow.

## Context

- `.github/workflows/release.yml` (ADR-0024) builds `signaldock-sdk-android-X.Y.Z-maven.zip` and its `.sha256` in the `build` job. The `publish` job downloads them and creates the GitHub Release.
- The `.sha256` file shows only that the download is intact. It lives next to the ZIP, so it does not show who built the ZIP.
- `actions/attest-build-provenance` v4.2.2 (latest release, 2026-08-06) makes a signed SLSA build provenance statement with a short-lived Sigstore certificate. For a public repository it uses the public Sigstore instance and stores the attestation in the GitHub attestations API.
- Source: the README of `actions/attest-build-provenance` v4.2.2 says artifact attestations are available in public repositories on all current GitHub plans (Free, Pro, Team). This repository is public and owned by a personal account. The action needs only workflow permissions, so no repository setting changes.
- Since v4 the action is a wrapper on `actions/attest`, which the README names for new work. The wrapper pins `actions/attest` v4.2.1 by SHA. The user chose the wrapper action; it stays supported.

## Decision

1. The `publish` job runs `actions/attest-build-provenance` with `subject-path: dist/*.zip`. The action is pinned by full commit SHA with a tag comment, like the other actions (`4d101475d8b20a2381f78447822ac1eab6504dd8`, v4.2.2).
2. Permissions are set on the `publish` job, not on the workflow: `contents: write` (unchanged), `id-token: write`, `attestations: write`. The `build` job keeps `contents: read` only. `artifact-metadata: write` is not needed: it is for storage records of pushed container images.
3. The step runs in `publish`, not in `build`. `build` runs project code (Gradle, dependencies); it must not get `id-token: write`. `publish` runs no project code (ADR-0024 item 4). The attested digest is the digest of the ZIP that `publish` downloads from the `build` artifact.
4. The step runs before `gh release create`. If it fails, no release exists, and the job can run again (ADR-0024).
5. Only the ZIP is a subject. The ZIP is the one unit that a consumer downloads. The `.sha256` file is derived from it. Rejected: attesting the files inside the ZIP (`.aar`, `.pom`, `.module`). A consumer adds the unzipped folder as a Maven repository and does not download these files alone. The ZIP digest already covers them. Per-file subjects need an unzip step and add many subjects with no user need.
6. Consumers verify with `gh attestation verify <zip> --repo HyeokjaeChoi/signaldock`. A stricter check adds `--signer-workflow HyeokjaeChoi/signaldock/.github/workflows/release.yml`. Use GitHub CLI 2.102.0 or newer: versions 2.51.0 to 2.101.0 treat `--signer-workflow` as a prefix (GHSA-wjmr-j3rp-mh2g; found by the CodeRabbit review). The commands are in `docs/releases/README.md` and `README.md`.

## Consequences

- Release `v0.2.0` and `v0.1.0` have no attestation. Only the first tag after this change has one.
- Cost: two more permissions on one job, and one more third-party action (Renovate proposes its updates, ADR-0031).
- The attestation proves which workflow, repository, and commit built the ZIP. It does not prove that the source is free of bugs, or that the dependencies are safe.

## Gaps

- Closed on 2026-10-21 (see the Amendment): the first release, `v0.3.0`, ran the attestation step and `gh attestation verify`. Original Gap: the workflow change was checked with a YAML parse and `actionlint` 1.7.12 only. No tag was pushed, so the attestation step and `gh attestation verify` have not run. The attestation is not produced until the next release tag. The first release after this change must run `gh attestation verify` on the new ZIP, and the result goes into the release record.
- Gap: the verified digest is that of the ZIP in `publish`. A compromised `build` job could still hand over a bad ZIP, and `publish` would sign it. This matches the common GitHub pattern; the signed workflow identity and commit make the event auditable.
- Gap: no SBOM attestation. No SLSA level is claimed.

## Amendment (2026-10-21)

The first run of the attestation step passed. Tag `v0.3.0` on `97abd80` ran release workflow run 39634744975 (`build` and `publish` succeeded). `gh attestation verify signaldock-sdk-android-0.3.0-maven.zip --repo HyeokjaeChoi/signaldock --signer-workflow HyeokjaeChoi/signaldock/.github/workflows/release.yml` (GitHub CLI 2.102.0) exited 0. The attested subject digest equals the `.sha256` file, the signer is `release.yml@refs/tags/v0.3.0`, and the source commit is `97abd80`. Record: [issue 78](../../.scratch/signaldock/issues/78-release-v0-3-0.md). This closes the first Gap above. The SBOM Gap stays.
