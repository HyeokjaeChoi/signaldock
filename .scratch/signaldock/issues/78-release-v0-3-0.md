# 78. Release v0.3.0

Status: resolved
Type: task
Blocked by: None
Execution: Done (merged in PR #196, 2026-10-21 16:25:56 UTC)

## Scope and completion criteria

Prepare the v0.3.0 SDK release PR by `docs/releases/README.md` "Cut a release" steps 1 to 5. No breaking change: the public API and the runtime dependency tree are the same as in 0.2.0 ([versioning](../../../docs/versioning.md)).

1. `sdkVersion` is 0.3.0. Install steps, integration guides (English and Korean), and the consumer app name 0.3.0. The wire constant `SDK_VERSION` is 0.3.0 too. Historical 0.1.0 and 0.2.0 references stay.
2. `docs/releases/v0.3.0.md` exists, with an "Upgrading from 0.2.0" section.
3. `CHANGELOG.md`: `[Unreleased]` is renamed `[0.3.0] - 2026-10-21`, and a new empty `[Unreleased]` exists.
4. `scripts/release.sh 0.3.0` ran locally. `dist/` is not committed.
5. No tag and no GitHub Release in this PR. The maintainer tags the merge commit. After the release workflow succeeds, run `gh attestation verify` on the ZIP (first attestation, ADR-0039) and record the result here.

## Comments

- 2026-10-21 UTC: PR #196 merged. Tag `v0.3.0` on `97abd80`. Release release workflow run 39634744975 succeeded (build, publish): https://github.com/HyeokjaeChoi/signaldock/releases/tag/v0.3.0. Downloaded assets: `shasum -a 256 -c signaldock-sdk-android-0.3.0-maven.zip.sha256` printed OK. `gh attestation verify` with `--repo HyeokjaeChoi/signaldock --signer-workflow HyeokjaeChoi/signaldock/.github/workflows/release.yml` (GitHub CLI 2.102.0) exited 0. The JSON output shows subject digest `c7049567...061c` (equal to the `.sha256` file), signer `release.yml@refs/tags/v0.3.0`, source commit `97abd80`. The CLI printed no text in a non-interactive shell, so the check is the exit code and the JSON fields, not the `Verification succeeded!` line.
