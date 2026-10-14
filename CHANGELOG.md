# Changelog

All notable changes to SignalDock are listed here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). The SDK follows [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Public API check for the Android SDK. `sdk/gradlew -p sdk :sdk-android:apiCheck` fails when the public API of `sdk-android` differs from the committed dump `sdk/sdk-android/api/sdk-android.api`. `scripts/verify-contract.sh` runs it ([ADR-0019](docs/adr/0019-sdk-public-api-check.md)).
- This changelog.
- Optional tracking plan: `tracking-plan/plan.json` describes the demo app events. `node tracking-plan/generate.mjs` generates typed Kotlin functions (demo app) and TypeScript types and builders. The SDK and its public API are unchanged ([ADR-0021](docs/adr/0021-tracking-plan-codegen.md)).
- Host-cost measurement: `scripts/host-cost-measure.sh` measures APK size, startup time, and PSS of the consumer app with and without the SDK on an emulator. `-Psignaldock.sdk=false` builds the no-SDK baseline. Results are in `perf/sdk-results/host-cost-api37/` ([ADR-0020](docs/adr/0020-sdk-host-app-cost.md)).

## [0.1.0] - 2026-10-14

First tagged release. License: Apache-2.0. See the [release notes](docs/releases/v0.1.0.md).

### Added

- The Maven repository ZIP `signaldock-sdk-android-0.1.0-maven.zip` and its `.sha256` file. The coordinate is `local.signaldock:sdk-android:0.1.0`. Maven Central is not used.
- The source of the Android SDK, the server, the Dashboard, the demo app, the consumer app, and the measurement harnesses, as in the tagged commit.

### Requirements

- The AAR needs `compileSdk` 36 or newer, `minSdk` 24 or newer, Kotlin 2.2 or newer, and AGP 9.x.

### Not verified

- Physical devices, the 216-run server pool-size matrix, release signing and Play distribution, a consumer on a Kotlin version other than the AGP 9.3.3 default (2.2.10) or on AGP 8.x, and public internet operation.

[Unreleased]: https://github.com/HyeokjaeChoi/signaldock/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/HyeokjaeChoi/signaldock/releases/tag/v0.1.0
