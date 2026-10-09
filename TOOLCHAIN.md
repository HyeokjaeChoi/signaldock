# Toolchain pins (issue #1)

Single source of truth: `gradle/libs.versions.toml`. This file records why each
version was chosen and what actually verified it.

"Build-verified" means a real build/codegen/test ran with the pinned version.
"Doc-pinned" means the version comes from official release metadata and will be
build-verified when its module is implemented (see the table below for the
tracking issue per component).

## Build-verified in issue #1

| Component | Pinned | Why |
|---|---|---|
| JDK | 17 (Ubuntu `openjdk-17-jdk-headless`) | AGP 8.13 requires JDK 17 (developer.android.com) |
| Gradle | 8.14.5 | >= AGP 8.13.2 minimum; inside Kotlin 2.4.x supported range (<= 9.7.0) |
| Kotlin | 2.4.21 | Latest stable; supports Gradle 7.6.3-9.7.0 and AGP 8.5.2-9.3.1 (kotlinlang.org) |
| kotlinx.serialization | 1.11.0 | Latest stable; compiler plugin applied in contract-check build |
| openapi-generator-cli | 7.26.0 | Latest stable; used by `scripts/codegen.sh` |
| oasdiff | v1.33.0 | Latest release; self-diff of contract-v1.yaml returns `{}` exit 0 |
| TypeScript | 7.0.2 | Latest stable via npm; compiles generated `typescript-fetch` models |
| Node | 24.20.0 | Environment version at verification time |

AGP 9.x was deliberately NOT chosen: it is a breaking major migration
(built-in Kotlin conflicts with the KGP plugin, kapt removal, DSL changes).
The 8.x line is the stable, documented path for this project.

## Doc-pinned (build verification deferred to the noted issue)

| Component | Pinned | Verified in |
|---|---|---|
| AGP | 8.13.2 (latest stable 8.x) | #3 (SDK module compile) |
| KSP | — no 2.4.x release as of 2026-10-09 | #3/#4 (Room/KSP modules) |
| Room | 2.8.5 | #3 |
| WorkManager | 2.12.0 | #4 |
| Retrofit | 3.0.0 | #4 |
| OkHttp | 5.5.0 | #4 |
| Compose BOM | 2026.09.00 | #5 |
| Ktor | 3.6.0 | #2 |
| jOOQ | 3.21.9 | #2 |
| Flyway | 13.10.0 | #2 |
| compileSdk 36 / targetSdk 36 / minSdk 24 | minSdk 24 per spec agreement | #3 |

## Evidence locations

- Contract source: `openapi/contract-v1.yaml` (prior-schema fixture baseline for oasdiff)
- Generated models (tracked per Q91): `generated/kotlin/`, `generated/typescript/`
- Round-trip fixtures: `fixtures/roundtrip/`
- Verification build: `contract-check/` (compiles generated Kotlin, runs fixture tests)
- Independent builds: `sdk/`, `server/` (own settings + wrappers, shared catalog)
- Root scripts: `scripts/codegen.sh`, `scripts/verify-contract.sh`
