# Toolchain pins

Single source of truth: `gradle/libs.versions.toml`. This file records why each
version was chosen and what actually verified it.

"Build-verified" means a real build/codegen/test ran with the pinned version.
Every pin below is build-verified. The section titles name the issue that
verified it. Some sections, such as "Build-verified in issue #1", also hold
the later changes of their pins.

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
| Gradle | 9.8.1, all four wrappers (`sdk/`, `server/`, `contract-check/`, `compatibility/consumer-android/`), `distributionSha256Sum` pinned | Exists on services.gradle.org; SHA-256 from https://services.gradle.org/distributions/gradle-9.8.1-bin.zip.sha256 (identical in all four `gradle-wrapper.properties`). Bumped from 9.7.0 to 9.8.1 in PRs #117 to #120. Build-verified on 9.8.1: CI run 39615361733 on `main` @ `a12d05b` passed `sdk-test`, `server-test`, `verify-contract`, `consumer-r8`, and `device-test`. This is newer than the Gradle range that the Kotlin row cites (up to 9.7.0); the CI run is the evidence |
| KSP / Room | 2.3.12 / 2.8.5 | Kotlin 2.4.21 + AGP 9.3.3 built-in Kotlin: same `sdk` command above (Room schema export and DAO generation ran) |
| Robolectric | 4.17 at `sdk=36` | Same `sdk` command. Test JVM gets `--add-exports=java.base/jdk.internal.access=ALL-UNNAMED`; without it all 34 Robolectric tests fail on JDK 21 with "Failed to interact with raw FileDescriptor internals" |
| SDK `aarMetadata.minCompileSdk` | 36 | Deliberate: the SDK is built and tested at compileSdk 36, so consumers must compile with API 36 or newer. Lowering it is unverified |
| SDK Java/Kotlin bytecode target | 17 | Stays below the JDK 21 that runs the build; AGP desugaring covers minSdk 24 |

Why AGP 9.3.3, Gradle 9.7.0 (the choice in issue #11; the current pin is 9.8.1, see the Gradle row), and JDK 21 (issue #11, replaces the earlier choice of
AGP 8.13.2 / Gradle 8.14.5 / JDK 17): the project is greenfield, so the AGP 9
migration cost does not apply. It brings built-in Kotlin (no
`org.jetbrains.kotlin.android` plugin; the SDK uses `kotlin { compilerOptions {} }`),
and Robolectric can run at SDK 36 and jOOQ 3.21 (needs Java 21). Kotlin 2.4 needs
R8 9.1.29 or newer (https://developer.android.com/build/kotlin-support); AGP bundles R8, so a current
AGP is the way to meet it. The R8 inside AGP 9.3.3 is 9.3.28, read from the `mapping.txt`
header of the consumer app's release build (issue 06, ADR-0003 item 9, ADR-0009). `android.builtInKotlin` and `android.newDsl` are
not turned off. Because of the new DSL, generated Kotlin sources are added with
`androidComponents.onVariants { variant.sources.kotlin }`; the old `sourceSets` accessor fails with a ClassCastException.

## Build-verified in issue #4

| Component | Pinned | Why |
|---|---|---|
| WorkManager | 2.12.0 | `work-runtime` and `work-testing`; `sdk/gradlew -p sdk :sdk-android:testDebugUnitTest` ran Worker and unique-work tests under Robolectric |
| Retrofit | 3.0.0 | Real requests against MockWebServer (`TransportTest`) |
| OkHttp | 5.4.0 (was doc-pinned at 5.5.0) | The `okhttp-android` 5.5.0 AAR declares `minCompileSdk=37`; the SDK builds at compileSdk 36, and AGP fails `checkDebugUnitTestAarMetadata`. 5.4.0 passes. `mockwebserver3` uses the same version. See ADR-0006 decision 1 |

## Build-verified in issue #15

Versions were read from `npm view <pkg> version` and Docker Hub tag listings on 2026-10-12, then pinned exactly (`--save-exact`, committed `dashboard/package-lock.json`). Local Node was v24.14.1 (npm 11.11.0), not the 24.20.0 recorded above; the Dashboard Docker build ran on `node:24.20.0`. All commands below ran in `dashboard/` unless noted.

| Component | Pinned | Why and how verified |
|---|---|---|
| react / react-dom | 19.3.0 | Latest stable. `npm test`, `npm run build` |
| recharts | 3.10.1 | Latest stable; accepts React 19. Renders in the built Dashboard (headless Chrome screenshot) |
| vite / @vitejs/plugin-react | 8.3.4 / 6.1.2 | Latest stable. `npm run build`, dev proxy checked with `npm run dev` |
| vitest / jsdom / @testing-library/react / @testing-library/dom | 5.0.5 / 30.1.3 / 16.3.3 / 10.4.2 | Latest stable (the last is the peer of testing-library/react). `npm test`: 174 tests in 12 files |
| @types/react, @types/react-dom / @types/node | 19.3.0 / 24.19.2 | Latest stable for each major in use; Node types follow the Node 24 line. `npm run typecheck` with TypeScript 7.0.2 |
| Node image | `node:24.22.0-alpine3.24` @ `sha256:44fce696f38ba8c987c4db89954051000bbd05ffd8d8659ba15ed4a8602e937f` | Updated by Renovate PR #190 (`dashboard/Dockerfile`). `docker compose build dashboard` |
| nginx image | `nginxinc/nginx-unprivileged:1.31.7-alpine3.24` @ `sha256:b5047b9b76845eac124358373c6852377c52e4b05834a47647e955dd91cb8f1e` | Changed in PR #211 (ADR-0045). The Dashboard Dockerfile also runs `apk upgrade --no-cache brotli-libs`, added in PR #217 to fix CVE-2026-111497 (no 1.31 alpine3.24 tag has brotli-libs 1.2.1-r0 yet); remove it when a base tag has it. Renovate keeps the base current (`docker:pinDigests`). Runs as user nginx on port 8080 (ADR-0045). Serves the bundle and proxies `/v1/` with the key (compose run) |
| JDK images | `eclipse-temurin:21.0.12.1_1-jdk-noble` @ `sha256:10721595...e964`, `...-jre-noble` @ `sha256:35501d3f...2c4c` | Same Temurin build as the pinned JDK 21.0.12.1+1. `installDist` in the image, server started under the JRE image (full digests in the Dockerfile) |
| PostgreSQL image | `postgres:17.6-alpine` @ `sha256:ef257d85...ee94` | Same pin the server tests and jOOQ codegen use |

Docker 29.4.0 and Compose v5.1.2 (OrbStack) ran `docker compose up -d --build`.

## Build-verified in issue #6

| Component | Pinned | Why and how verified |
|---|---|---|
| R8 (bundled in AGP 9.3.3) | 9.3.28 | `scripts/verify-consumer.sh`: `compatibility/consumer-android` `assembleRelease` with `isMinifyEnabled = true`; `app/build/outputs/mapping/release/mapping.txt` header `# compiler_version: 9.3.28`. Meets the 9.1.29 minimum for Kotlin 2.4 (the SDK compiler) |
| Consumer Kotlin | 2.2.10 (AGP 9.3.3 built-in default, no override) | The SDK sets `languageVersion`/`apiVersion` 2.2 and stdlib 2.2.21, so its metadata is 2.2.0 (Q132, ADR-0009 decision 4). `scripts/verify-consumer.sh` checks this |
| Maven publication | `maven-publish`, `local.signaldock:sdk-android:0.1.0` | `sdk/gradlew -p sdk :sdk-android:signaldockMavenZip` (AAR, sources jar, POM, Gradle module metadata) |
| Android emulator run | AVD `signaldock-api37`, system image android-37.2, headless | Consumer release APK installed; `track()` then `lastAck accepted=1`; the row was in PostgreSQL |

## Build-verified in issue #20

| Component | Pinned | Why and how verified |
|---|---|---|
| androidx.test runner / ext:junit | 1.7.0 / 1.3.0 | Latest stable on Google Maven, 2026-10-12 (core stays 1.7.0). `sdk/gradlew -p sdk :sdk-android:assembleDebugAndroidTest` and `connectedDebugAndroidTest` on both AVDs |
| Instrumented test device images | AVD `signaldock-api24` (API 24, google_apis, arm64) and `signaldock-api37` (API 37.2), headless | Start with `$ANDROID_HOME/emulator/emulator -avd <name> -no-window -no-audio -no-boot-anim`. Commands, results and limits: [ADR-0011](docs/adr/0011-sdk-device-test-harness.md), `scripts/device-durability.sh` |

## Build-verified in issue #23

| Component | Pinned | Why and how verified |
|---|---|---|
| react / react-dom / recharts | 19.3.0 / 19.3.0 / 3.10.1 | Same versions as `dashboard/package.json`. `perf/report/` (own `package-lock.json`): `npm ci && npm run build` |
| vite / @vitejs/plugin-react / typescript | 8.3.4 / 6.1.2 / 7.0.2 | Same as the Dashboard. `@types/react`, `@types/react-dom` 19.3.0 |
| pg_stat_statements | contrib module of `postgres:17.6-alpine` | No new image. Benchmark stack only (`perf/compose.perf.yml`); ADR-0013 |

## Build-verified in issues #2, #3, and #16

| Component | Pinned | Why and how verified |
|---|---|---|
| KSP | 2.3.12 | No 2.4.x release as of 2026-10-09 (comment in `gradle/libs.versions.toml`). KSP 2.3.12 ran with Kotlin 2.4.21 for Room in `sdk/gradlew -p sdk :sdk-android:testDebugUnitTest` (issue #1 table above; issue 03) |
| Compose BOM | 2026.09.00 | Demo app build (issue 16, ADR-0006 decision 1) |
| Ktor | 3.6.0 | Server tests and the Docker server image (issues 02 and 15) |
| Flyway | 13.10.0 | Server migrations and `SchemaUpgradeTest` (issues 02 and 22, ADR-0012) |
| Trivy | `aquasec/trivy:0.76.1@sha256:790fd66d1697395d8693d1acaa1ea702406a20f109b6fc7bac8db623eca6c06c` (multi-arch index digest) | CI image scan in `.github/workflows/demo-deploy.yml` (ADR-0045 item 6). Official Docker Hub image, run with `docker run`; no token, no registry credential. Renovate does not read this `docker run` line, so the digest is updated by hand |
| compileSdk 36 / targetSdk 36 / minSdk 24 | minSdk 24 per spec agreement | The SDK builds at compileSdk 36 (issue 03). The demo app uses compileSdk 37 (`demo-compileSdk`, issue 16); it needs platform `android-37` |

## Evidence locations

- Contract source: `openapi/contract-v1.yaml` (oasdiff compares it with `fixtures/contract/contract-v1.baseline.yaml`)
- Generated models (tracked per Q91): `generated/kotlin/`, `generated/typescript/`
- Round-trip fixtures: `fixtures/roundtrip/`
- Compatibility fixtures: `fixtures/compat/` (frozen prior request replayed against the server, new ACK read by prior models; ADR-0012)
- DB change check: `server/persistence` `SchemaUpgradeTest` and `src/test/resources/db/previous-data/V<n>.sql` (ADR-0012)
- Verification build: `contract-check/` (compiles generated Kotlin, runs fixture tests)
- Independent builds: `sdk/`, `server/`, `compatibility/consumer-android/` (own settings + wrappers, shared catalog; the consumer takes the SDK only from the Maven ZIP). `server/` has the modules `:app`, `:ingestion`, `:persistence`; `cd server && ./gradlew test verifyJooq` runs all of them
- Root scripts: `scripts/codegen.sh`, `scripts/verify-contract.sh`, `scripts/verify-consumer.sh`, `scripts/check-size-budget.sh`
- Dependency updates: Renovate (`.github/renovate.json5`, ADR-0031). It proposes no new major for the JDK 21, Node 24, and nginx images, the PostgreSQL image, or `@types/node`; those pins change by hand
- Public API check: `org.jetbrains.kotlinx.binary-compatibility-validator` 0.18.2 (ADR-0019). The dump is `sdk/sdk-android/api/sdk-android.api`
- Dependency baseline: `com.dropbox.dependency-guard` 0.5.0 (ADR-0033). Baselines are in `sdk/sdk-android/dependencies/`
- Code scanning: CodeQL advanced setup, `.github/workflows/codeql.yml` (ADR-0032)
- Gradle wrapper validation: CI job `gradle-wrapper` in `.github/workflows/repo-rules.yml` (Q162)
- Gradle dependency verification: SHA-256 checksums in `sdk/`, `server/`, `contract-check/`, and `compatibility/consumer-android/` `gradle/verification-metadata.xml` (Q166, ADR-0038). After a dependency change run `scripts/update-gradle-verification.sh`
- Baseline Profile: `androidx.baselineprofile` 1.5.0, `androidx.benchmark:benchmark-macro-junit4` 1.5.0, `androidx.test.uiautomator:uiautomator` 2.4.0, and `androidx.profileinstaller:profileinstaller` 1.4.1 (the last only in `sdk/profile-app`). Latest stable on Google Maven on 2026-10-17 UTC. Verified: `sdk/gradlew -p sdk :sdk-android:generateBaselineProfile` and `:baseline-profile:connectedBenchmarkReleaseAndroidTest` ran with AGP 9.3.3 and Gradle 9.7.0 on the API 37 emulator `faults37` (ADR-0040). Not part of CI
