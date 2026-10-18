# 68. Gradle dependency verification

Status: claimed
Type: task
Blocked by: None
Execution: Branch `ci/gradle-dependency-verification`, PR pending

## Scope and completion criteria

Spec Q166. The decisions are in ADR-0038. CI and docs change. No product code changes.

1. Each of the four Gradle builds (`sdk`, `server`, `contract-check`, `compatibility/consumer-android`) has `gradle/verification-metadata.xml` with SHA-256 checksums. A dependency that is not in the file fails the build.
2. The file covers every configuration that CI uses: lint, unit tests, `assembleDebugAndroidTest`, R8 release builds, `dependencyGuard`, `apiCheck`, the Maven ZIP, the CodeQL manual build, and the server tests with `verifyJooq`.
3. `local.signaldock` (the SDK that the consumer app reads from a folder) is trusted, because its checksum changes at each build.
4. `scripts/update-gradle-verification.sh` rewrites the four files with the same tasks that CI runs.
5. The ADR records the Renovate finding (hosted Renovate does not update these files for this repo) and the manual step for Renovate PRs.
6. Docs: ADR-0038, spec Q166, `TOOLCHAIN.md`, `CONTRIBUTING.md`, `docs/releases/README.md`, threat model supply-chain row, `docs/design-index.md`, `CHANGELOG.md`.

Verification:
- `sdk/gradlew -p sdk build :sdk-android:assembleDebugAndroidTest --no-daemon`; `server/gradlew -p server test verifyJooq --no-daemon`; `scripts/verify-contract.sh`; `scripts/verify-consumer.sh`; `scripts/check-size-budget.sh`; `scripts/check-repo-rules.sh`.
