# Changelog

All notable changes to SignalDock are listed here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). The SDK follows [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Contract deprecation warning in the Android SDK. When a server response (any HTTP status) has a valid `Deprecation` (RFC 9745) or `Sunset` (RFC 8594) header, the SDK writes one WARN per process and shows the dates in the new `Diagnostics.contractDeprecation` (type `ContractDeprecation`). Delivery does not change; invalid or absent headers are ignored. Behavior change: a `Retry-After` HTTP-date with trailing text is now ignored (it was accepted before). The public API change is only additive (a new getter and a new class); `Diagnostics` constructor, `copy`, and `componentN` are unchanged. The server does not send these headers yet ([ADR-0030](docs/adr/0030-sdk-contract-deprecation-warning.md)).
- Server tracking plan status: when the server has a plan (`SIGNALDOCK_TRACKING_PLAN`, mounted by Compose from `tracking-plan/plan.json`), it marks each new stored event `planned`, `unplanned_event`, or `invalid_properties`. It never rejects or drops an event for plan reasons; the SDK and the ACK are unchanged. The contract adds an optional `planStatus` on received events, a `planStatus` filter, and `GET /v1/event-counts/by-plan-status` (compatible, contract version 1). The Dashboard shows a status badge, a filter, and a violations card. Migration `V2__add_plan_status.sql` adds a nullable column; events stored before it have no status ([ADR-0022](docs/adr/0022-server-tracking-plan-status.md)).
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
