# Toolchain pins (issues #1 and #11)

Single source of truth: `gradle/libs.versions.toml`. This file records why each
version was chosen and what actually verified it.

"Build-verified" means a real build/codegen/test ran with the pinned version.
"Doc-pinned" means the version comes from official release metadata and will be
build-verified when its module is implemented (see the table below for the
tracking issue per component).

## Setup

Run `scripts/setup-toolchain.sh` once per clone. It installs the pinned JDK,
oasdiff, and openapi-generator-cli into `.tools/`. It checks the hash of each
download and skips tools that are already installed. It works on macOS and
Linux, on arm64 and x64.

If a tool that `scripts/setup-toolchain.sh` installs is missing, a script stops with a setup message. Scripts use the installed oasdiff and openapi-generator-cli. They use the bundled JDK unless `JAVA_HOME` is set. Node and npm come from PATH; 24.20.0 is the verified Node version.

Lockfiles are committed. Verification runs `npm ci`.

## Build-verified in issue #1

| Component | Pinned | Why |
|---|---|---|
| JDK | Eclipse Temurin 21.0.12.1+1, in `.tools/jdk-21.0.12.1+1` | jOOQ 3.21 needs Java 21; Robolectric SDK 36 needs JDK 21. Verified: `scripts/setup-toolchain.sh` (SHA-256 from the Adoptium API, 2026-10-11) and every Gradle run below. Source: https://api.adoptium.net/v3/assets/latest/21/hotspot?image_type=jdk&vendor=eclipse |
| Kotlin | 2.4.21 | Latest stable; supports Gradle 7.6.3-9.7.0 and AGP 8.5.2-9.3.1 (kotlinlang.org); also ran with AGP 9.3.3 in issue #11 |
| kotlinx.serialization | 1.11.0 | Latest stable; compiler plugin applied in contract-check build |
| openapi-generator-cli | 7.26.0 | Latest stable; used by `scripts/codegen.sh` |
| oasdiff | v1.33.0 | Latest release; `scripts/verify-contract.sh` runs `oasdiff diff --fail-on-diff` on the baseline and the contract |
| TypeScript | 7.0.2 | Latest stable via npm; compiles generated `typescript-fetch` models |
| Node | 24.20.0 | Environment version at verification time |
| jOOQ | 3.21.9 | Latest 3.21.x on Maven Central (https://repo1.maven.org/maven2/org/jooq/jooq/maven-metadata.xml). `cd server && ./gradlew test verifyJooq --no-daemon` passed on JDK 21 after `generateJooq` refreshed the tracked sources (generator output changes for jOOQ 3.21: 7 files, 15 insertions, 14 deletions) |
| AGP | 9.3.3 | Latest 9.3.x on Google Maven (https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml); requires Gradle 9.5.0+ (https://developer.android.com/build/releases/about-agp). `sdk/gradlew -p sdk :sdk-android:testDebugUnitTest :sdk-android:assembleRelease --no-daemon` passed (35 tests, 0 failures) |
| Gradle | 9.7.0, all three wrappers, `distributionSha256Sum` pinned | Exists on services.gradle.org; SHA-256 from https://services.gradle.org/distributions/gradle-9.7.0-bin.zip.sha256. Wrappers regenerated with `./gradlew wrapper --gradle-version 9.7.0 --gradle-distribution-sha256-sum <sha>`; all verification commands ran on it |
| KSP / Room | 2.3.12 / 2.8.5 | Kotlin 2.4.21 + AGP 9.3.3 built-in Kotlin: same `sdk` command above (Room schema export and DAO generation ran) |
| Robolectric | 4.17 at `sdk=36` | Same `sdk` command. Test JVM gets `--add-exports=java.base/jdk.internal.access=ALL-UNNAMED`; without it all 34 Robolectric tests fail on JDK 21 with "Failed to interact with raw FileDescriptor internals" |
| SDK `aarMetadata.minCompileSdk` | 36 | Deliberate: the SDK is built and tested at compileSdk 36, so consumers must compile with API 36 or newer. Lowering it is unverified |
| SDK Java/Kotlin bytecode target | 17 | Stays below the JDK 21 that runs the build; AGP desugaring covers minSdk 24 |

Why AGP 9.3.3, Gradle 9.7.0, and JDK 21 (issue #11, replaces the earlier choice of
AGP 8.13.2 / Gradle 8.14.5 / JDK 17): the project is greenfield, so the AGP 9
migration cost does not apply. It brings built-in Kotlin (no
`org.jetbrains.kotlin.android` plugin; the SDK uses `kotlin { compilerOptions {} }`),
and Robolectric can run at SDK 36 and jOOQ 3.21 (needs Java 21). Kotlin 2.4 needs
R8 9.1.29 or newer (https://developer.android.com/build/kotlin-support); AGP bundles R8, so a current
AGP is the way to meet it. The R8 version inside AGP 9.3.3 was not read from a
build output (see ADR-0003 item 9). `android.builtInKotlin` and `android.newDsl` are
not turned off. Because of the new DSL, generated Kotlin sources are added with
`androidComponents.onVariants { variant.sources.kotlin }`; the old `sourceSets` accessor fails with a ClassCastException.

## Doc-pinned (build verification deferred to the noted issue)

| Component | Pinned | Verified in |
|---|---|---|
| KSP | — no 2.4.x release as of 2026-10-09 | #3/#4 (Room/KSP modules) |
| WorkManager | 2.12.0 | #4 |
| Retrofit | 3.0.0 | #4 |
| OkHttp | 5.5.0 | #4 |
| Compose BOM | 2026.09.00 | #5 |
| Ktor | 3.6.0 | #2 |
| Flyway | 13.10.0 | #2 |
| compileSdk 36 / targetSdk 36 / minSdk 24 | minSdk 24 per spec agreement | #3 |

## Evidence locations

- Contract source: `openapi/contract-v1.yaml` (oasdiff compares it with `fixtures/contract/contract-v1.baseline.yaml`)
- Generated models (tracked per Q91): `generated/kotlin/`, `generated/typescript/`
- Round-trip fixtures: `fixtures/roundtrip/`
- Verification build: `contract-check/` (compiles generated Kotlin, runs fixture tests)
- Independent builds: `sdk/`, `server/` (own settings + wrappers, shared catalog). `server/` has the modules `:app`, `:ingestion`, `:persistence`; `cd server && ./gradlew test verifyJooq` runs all of them
- Root scripts: `scripts/codegen.sh`, `scripts/verify-contract.sh`
