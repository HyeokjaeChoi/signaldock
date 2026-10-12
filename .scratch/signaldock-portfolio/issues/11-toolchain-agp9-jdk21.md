# 11. Toolchain upgrade: AGP 9.3, Gradle 9.7, JDK 21

Status: ready-for-review
Type: task
Blocked by: None
Execution: Done (merged in PR #20, 2026-10-11)

## Scope and completion criteria

The issue 01 pins (AGP 8.13.2, Gradle 8.14.5, JDK 17) were an implementer choice, not an agreed design decision. Their reason ("AGP 9 is a breaking migration") does not apply to a greenfield project. They caused three workarounds: R8 too old for Kotlin 2.4 (ADR-0003 item 9), Robolectric at SDK 35, and jOOQ 3.19 (ADR-0004).

Upgrade to JDK 21, AGP 9.3.3 (built-in Kotlin, no `kotlin-android` plugin), Gradle 9.7.0 in every build, jOOQ 3.21.9, Robolectric at SDK 36. Keep compileSdk 36 and the other pins. Update `TOOLCHAIN.md`, `gradle/libs.versions.toml`, wrappers, `scripts/setup-toolchain.sh`, CI, ADR-0003 item 9, and ADR-0004.

Done when SDK tests, server tests with `verifyJooq`, and `scripts/verify-contract.sh` pass on the new pins, with commands and results recorded here.

## Comments

2026-10-11: Created after the user approved the upgrade.

2026-10-11: Implemented. Pins: JDK Temurin 21.0.12.1+1 (Adoptium API, SHA-256 in `scripts/setup-toolchain.sh`), AGP 9.3.3, Gradle 9.7.0 with `distributionSha256Sum` (sdk, server, contract-check; wrappers regenerated), jOOQ 3.21.9, Robolectric 4.17 at `sdk=36`. Kotlin 2.4.21, KSP 2.3.12, Room 2.8.5, compileSdk/targetSdk 36 unchanged. `kotlin-android` plugin removed (built-in Kotlin); SDK uses `kotlin { compilerOptions { jvmTarget = JVM_17 } }`; `aarMetadata.minCompileSdk = 36`. Generated sources are added through `androidComponents.onVariants` because the old `sourceSets` accessor throws ClassCastException under AGP 9. Robolectric tests need `--add-exports=java.base/jdk.internal.access=ALL-UNNAMED` on JDK 21. `server/generated/jooq` regenerated with `generateJooq` (7 files). `jvmToolchain` is 21 for server and contract-check. Docs updated: TOOLCHAIN.md, ADR-0003 items 8 and 9, ADR-0004. Each run used `JAVA_HOME=$PWD/.tools/jdk-21.0.12.1+1` and `ANDROID_HOME=~/Library/Android/sdk`, from the worktree root.

Commands and results:
- `scripts/setup-toolchain.sh`: installed JDK 21.0.12.1+1, oasdiff, openapi-generator-cli (hash checks passed); second run printed three "skip:" lines (idempotent).
- `sdk/gradlew -p sdk :sdk-android:testDebugUnitTest :sdk-android:assembleRelease --no-daemon`: BUILD SUCCESSFUL; 35 tests, 0 failures (first run with `sdk=36` and no add-exports: 34 of 35 failed with "Failed to interact with raw FileDescriptor internals"; fixed by the test JVM argument). The final re-run after restoring the build file was mostly up to date (tests had run in the run before it, with identical inputs).
- `(cd server && ./gradlew test verifyJooq --no-daemon)`: first run failed at `verifyJooq` with "jOOQ source drift" (expected after the jOOQ bump); after `./gradlew generateJooq --no-daemon` the same command: BUILD SUCCESSFUL (tests passed, drift check passed).
- `scripts/verify-contract.sh`: ALL CONTRACT CHECKS PASSED (codegen drift, oasdiff, contract-check Gradle tests, dashboard TS checks, `help` for sdk and server).
- R8: a temporary `isMinifyEnabled = true` run succeeded (`:sdk-android:minifyReleaseWithR8` executed) but no R8 version was printed; the bundled R8 version is not verified. The temporary change was reverted.

2026-10-12: Record reconciled. Merged into main in PR #20 on 2026-10-11. Earlier branch and push notes above describe the state before that merge.
