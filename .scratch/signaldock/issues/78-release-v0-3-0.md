# 78. Release v0.3.0

Status: claimed
Type: task
Blocked by: None
Execution: In an open PR (2026-10-21 UTC)

## Scope and completion criteria

Prepare the v0.3.0 SDK release PR by `docs/releases/README.md` "Cut a release" steps 1 to 5. No breaking change: the public API and the runtime dependency tree are the same as in 0.2.0 ([versioning](../../../docs/versioning.md)).

1. `sdkVersion` is 0.3.0. Install steps, integration guides (English and Korean), and the consumer app name 0.3.0. The wire constant `SDK_VERSION` is 0.3.0 too. Historical 0.1.0 and 0.2.0 references stay.
2. `docs/releases/v0.3.0.md` exists, with an "Upgrading from 0.2.0" section.
3. `CHANGELOG.md`: `[Unreleased]` is renamed `[0.3.0] - 2026-10-21`, and a new empty `[Unreleased]` exists.
4. `scripts/release.sh 0.3.0` ran locally. `dist/` is not committed.
5. No tag and no GitHub Release in this PR. The maintainer tags the merge commit. After the release workflow succeeds, run `gh attestation verify` on the ZIP (first attestation, ADR-0039) and record the result here.
