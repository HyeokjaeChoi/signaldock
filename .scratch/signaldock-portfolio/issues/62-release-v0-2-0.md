# 62. Release v0.2.0

Status: claimed
Type: task
Blocked by: None
Execution: PR on branch `release/v0.2.0`

## Scope and completion criteria

Prepare the v0.2.0 SDK release PR by `docs/releases/README.md` "Cut a release" steps 1 to 5. Pre-1.0: breaking changes are in a MINOR release and are listed under "Changed" ([versioning](../../../docs/versioning.md)).

1. `sdkVersion` is 0.2.0. Install steps, integration guides (English and Korean), and the consumer app name 0.2.0. The wire constant `SDK_VERSION` is 0.2.0 too. Historical 0.1.0 references stay.
2. `docs/releases/v0.2.0.md` exists, with an "Upgrading from 0.1.0" section.
3. `CHANGELOG.md`: `[Unreleased]` is renamed `[0.2.0] - 2026-10-17`, and a new empty `[Unreleased]` exists.
4. `scripts/release.sh 0.2.0` ran locally. `dist/` is not committed.
5. No tag and no GitHub Release in this PR. The maintainer tags the merge commit.
