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
| vitest / jsdom / @testing-library/react / @testing-library/dom | 5.0.3 / 30.1.2 / 16.3.3 / 10.4.2 | Latest stable (the last is the peer of testing-library/react). `npm test`: 19 tests |
| @types/react, @types/react-dom / @types/node | 19.3.0 / 24.19.2 | Latest stable for each major in use; Node types follow the Node 24 line. `npm run typecheck` with TypeScript 7.0.2 |
| Node image | `node:24.20.0-alpine3.24` @ `sha256:e67514e5d0f6c46656005e1b693b2ec9d52e80b641307de684d4a015ba7a4eaf` | Same Node as the 24.20.0 record above (24.21.0 exists; staying on the verified minor). `docker compose build dashboard` |
| nginx image | `nginx:1.30.5-alpine3.24` @ `sha256:0985e772fb9f729e6fa0980da05fca5d9c468e870eed43071545afa9d2e27d94` | Latest 1.30 (stable branch) patch. Serves the bundle and proxies `/v1/` with the key (compose run) |
| JDK images | `eclipse-temurin:21.0.12.1_1-jdk-noble` @ `sha256:b468c3fc...2a87`, `...-jre-noble` @ `sha256:000fd431...408c` | Same Temurin build as the pinned JDK 21.0.12.1+1. `installDist` in the image, server started under the JRE image (full digests in the Dockerfile) |
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

## Doc-pinned (build verification deferred to the noted issue)

| Component | Pinned | Verified in |
|---|---|---|
| KSP | — no 2.4.x release as of 2026-10-09 | #3/#4 (Room/KSP modules) |
| Compose BOM | 2026.09.00 | #5 |
| Ktor | 3.6.0 | #2 |
| Flyway | 13.10.0 | #2 |
| compileSdk 36 / targetSdk 36 / minSdk 24 | minSdk 24 per spec agreement | #3 |

## Evidence locations

- Contract source: `openapi/contract-v1.yaml` (oasdiff compares it with `fixtures/contract/contract-v1.baseline.yaml`)
- Generated models (tracked per Q91): `generated/kotlin/`, `generated/typescript/`
- Round-trip fixtures: `fixtures/roundtrip/`
- Compatibility fixtures: `fixtures/compat/` (frozen prior request replayed against the server, new ACK read by prior models; ADR-0012)
- DB change check: `server/persistence` `SchemaUpgradeTest` and `src/test/resources/db/previous-data/V<n>.sql` (ADR-0012)
- Verification build: `contract-check/` (compiles generated Kotlin, runs fixture tests)
- Independent builds: `sdk/`, `server/`, `compatibility/consumer-android/` (own settings + wrappers, shared catalog; the consumer takes the SDK only from the Maven ZIP). `server/` has the modules `:app`, `:ingestion`, `:persistence`; `cd server && ./gradlew test verifyJooq` runs all of them
- Root scripts: `scripts/codegen.sh`, `scripts/verify-contract.sh`
- Dependency updates: Renovate (`.github/renovate.json5`, ADR-0031). It proposes no new major for the JDK 21 and Node 24 images, the PostgreSQL image, or `@types/node`; those pins change by hand
