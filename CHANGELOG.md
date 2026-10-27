# Changelog

All notable changes to SignalDock are listed here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). The SDK follows [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Cloud demo infrastructure: `infra/` (Terraform, host nginx, Compose override, VM scripts), the `demo-deploy` workflow, and `scripts/demo-seed.py` ([ADR-0043](docs/adr/0043-cloud-demo-hosting-and-deploy.md) to [ADR-0045](docs/adr/0045-cloud-demo-host-and-container-hardening.md), PRs #207 to #219 and #232 (Docker daemon hardening, Dashboard healthcheck)). The demo was deployed on 2026-10-25 UTC (tag `demo-v0.1.1`). No SDK change.

- Server support for the SDK platform (`sdkPlatform`, [ADR-0046](docs/adr/0046-sdk-platform-field.md), iOS SDK ticket 02): migration `V4`, the default `android` for a missing field, a per-item rejection of an unknown value, the `platform:version` kill switch, the `sdkPlatform` filter on five query endpoints, and client report counts by (platform, version). Old `SIGNALDOCK_BLOCKED_SDK_VERSIONS` items keep their meaning (they mean `android`).

### Fixed

- The Dashboard image upgrades `brotli-libs` for CVE-2026-111497 (PR #217).
- The Dashboard image upgrades `curl` and `libcurl` for CVE-2026-92392 (PR #242).
- The server retries the Flyway connect at start, 5 retries (PR #221).
- `scripts/demo-seed.py` treats any 503 as uncertain, and a later-day rerun stops at the sentinel (PR #229).
- The reset checks the 6 h guard again after it takes the lock (PR #230).

## [0.3.0] - 2026-10-21

Third tagged release. It has no breaking change: the public API and the runtime dependency tree are the same as in 0.2.0. See the [release notes](docs/releases/v0.3.0.md).

### Added

- Gradle dependency verification (Q166). Each of the four Gradle builds has `gradle/verification-metadata.xml` with SHA-256 checksums; a dependency or plugin file that is not listed, or that differs, fails the build. `scripts/update-gradle-verification.sh` rewrites the files after a dependency change. Renovate does not update them for this repo, so a Renovate Gradle PR needs that script run on its branch before CI passes. No product code or published POM changed ([ADR-0038](docs/adr/0038-gradle-dependency-verification.md)).
- Build provenance attestation for the release ZIP (Q167). The `publish` job of the release workflow signs a GitHub artifact attestation before it creates the release. Check it with `gh attestation verify <zip> --repo HyeokjaeChoi/signaldock`. It has not run on a real release yet: the first release tag after this change produces the first attestation; `v0.2.0` has none ([ADR-0039](docs/adr/0039-release-provenance-attestation.md)).
- The SDK AAR ships a Baseline Profile (`baseline-prof.txt`, 519 rules for `local.signaldock.**`) for `initialize()`, `track()`, the Room queue, and the sender. The host's AGP merges it into the app profile, so no host change is needed. The runtime dependency tree and the public API do not change. On one API 37 emulator, the p50 of the SDK startup work was 28 % and 53 % lower than with no compilation in two runs and 1 % lower in a third, and the SDK rules alone gave no result outside the noise. New modules `sdk/profile-app` and `sdk/baseline-profile` generate and measure the profile (not published). Regenerate with `sdk/gradlew -p sdk :sdk-android:generateBaselineProfile` on an emulator ([ADR-0040](docs/adr/0040-sdk-baseline-profile.md), spec Q168).
- Server-side collection kill switch (Q165, option A). The operator can block events by exact name (`SIGNALDOCK_BLOCKED_EVENTS`) and by exact event `sdkVersion` (`SIGNALDOCK_BLOCKED_SDK_VERSIONS`); a blocked event gets the existing per-item rejection with the new code value `COLLECTION_BLOCKED`, so the SDK deletes it and counts `server_rejected`. `SIGNALDOCK_INGEST_SHED=true` makes the two ingest routes answer 503 with `Retry-After` (`SIGNALDOCK_INGEST_SHED_RETRY_AFTER_SECONDS`, default 1). Environment variables read at start; default is nothing blocked. No SDK, wire, or contract change. The server cannot stop collection on the device ([ADR-0037](docs/adr/0037-server-collection-kill-switch.md)).

## [0.2.0] - 2026-10-17

Second tagged release. It has breaking changes before 1.0: see "Changed" and the upgrade steps in the [release notes](docs/releases/v0.2.0.md).

### Changed

- Breaking at the binary level (source compatible): the JVM constructor and `copy` signatures of `SignalDockConfig` change, because the new `allowInsecureEndpoint` parameter has a default. Recompile against this version. This is allowed before 1.0 when listed here ([docs/versioning.md](docs/versioning.md), [ADR-0035](docs/adr/0035-sdk-https-enforcement.md)).
- `TrackResult` has a new case `OptedOut`. Binary compatible. A host `when` expression over `TrackResult` without an `else` branch must handle it to compile ([ADR-0034](docs/adr/0034-sdk-opt-out.md)).
- Renovate replaces Dependabot for dependency updates. `.github/renovate.json5` reads the shared Gradle version catalog, which Dependabot could not read, and also updates the Gradle wrapper. `.github/dependabot.yml` is deleted ([ADR-0031](docs/adr/0031-renovate-dependency-updates.md), PR #144).
- The SDK clamps the stored retry wait when the device clock moves back. The Room database is now version 2 (`MIGRATION_1_2` adds `retry_state.waitMillis`); queued events and the retry state survive the update ([ADR-0025](docs/adr/0025-sdk-clock-jump.md), PR #109).
- The server answers a lone surrogate escape, a nested property value, or a `null` property value as a per-item rejection, not as a request-level 400. For a correct SDK, a request-level 400 now means a contract or configuration bug. The SDK wire format is checked against the server parser with the corpus `fixtures/sdk-wire/` ([ADR-0027](docs/adr/0027-request-400-contract-only.md), PR #113).

### Added

- Opt-out in the Android SDK. `SignalDock.setOptOut(Boolean)` turns collection off or on, `isOptedOut()` reads the state, and `clearQueue()` deletes all queued events. While opted out, `track()` stores nothing and returns the new `TrackResult.OptedOut`. Queued events stay and are still sent unless the host calls `clearQueue()`. The state is kept across restarts in the backup-excluded SDK directory. The public API change is additive ([ADR-0034](docs/adr/0034-sdk-opt-out.md), spec Q156).
- HTTPS enforcement in the Android SDK. `SignalDock.initialize` returns `InvalidConfiguration` for an endpoint whose scheme is not `https` (case-insensitive), unless the scheme is `http` and the new `SignalDockConfig.allowInsecureEndpoint` is `true` (default `false`); the flag permits only `http`, and other schemes stay rejected. Reason: a host that allows cleartext would send the collection key in plain text. Source compatible. An app with an `http` endpoint must set the flag. The demo and consumer apps set it when their endpoint starts with `http://` ([ADR-0035](docs/adr/0035-sdk-https-enforcement.md)).
- SDK client reports (Q164). The Android SDK counts the events it dropped (`queue_full`, `storage_error`, `invalid_event`, `event_too_large`, `server_rejected`), keeps the counters in Room (database schema 3, migration `MIGRATION_2_3`), and sends them to the new `POST /v1/client-reports` at most once per send cycle after a valid ACK. A 404, any other 4xx except 408 and 429, a 3xx, a 1xx, 501, or 505 discards the report; a network error, 408, 429, or another 5xx keeps the counters. The public API and the batch contract do not change. The contract adds `POST /v1/client-reports` and `GET /v1/client-report-counts` (compatible, contract version 1). The server stores one row per entry (migration `V3__create_client_reports.sql`, no installation ID), and the Dashboard shows a "Dropped events" card by SDK version and reason ([ADR-0036](docs/adr/0036-sdk-client-reports.md)).
- Dependency baseline for the Android SDK. `sdk/sdk-android/dependencies/` lists the release runtime and compile classpaths, and `scripts/verify-contract.sh` runs `:sdk-android:dependencyGuard`, which fails when the tree changes without an updated baseline. No code changed in the SDK ([ADR-0033](docs/adr/0033-sdk-dependency-baseline.md)).
- Contract deprecation warning in the Android SDK. When a server response (any HTTP status) has a valid `Deprecation` (RFC 9745) or `Sunset` (RFC 8594) header, the SDK writes one WARN per process and shows the dates in the new `Diagnostics.contractDeprecation` (type `ContractDeprecation`). Delivery does not change; invalid or absent headers are ignored. Behavior change: a `Retry-After` HTTP-date with trailing text is now ignored (it was accepted before). The public API change is only additive (a new getter and a new class); `Diagnostics` constructor, `copy`, and `componentN` are unchanged. The server does not send these headers yet ([ADR-0030](docs/adr/0030-sdk-contract-deprecation-warning.md)).
- Engineering judgment documents in `docs/engineering/`: a design review with scale analysis, a failure mode table, two postmortems, and a threat model. They mark the gaps they found. No code changed.
- CI runs the instrumented SDK tests (30 start, 13 skip themselves, 17 run; `repo-rules` run 39002096383) on an API 34 `google_apis` x86_64 emulator (`device-test` job, `reactivecircus/android-emulator-runner`, cached AVD snapshot), and a `size-budget` job (`scripts/check-size-budget.sh`) fails when the SDK adds more than 700,000 bytes to the R8 consumer APK. To raise the budget on purpose, change it in the script and update this changelog and the ADR in the same PR ([ADR-0023](docs/adr/0023-ci-device-tests-and-size-budget.md)).
- Community health files: `SECURITY.md`, `CONTRIBUTING.md`, issue forms and a pull request template in `.github/`, and the versioning and deprecation policy [docs/versioning.md](docs/versioning.md). Both READMEs list the supported versions. No code changed.
- Server tracking plan status: when the server has a plan (`SIGNALDOCK_TRACKING_PLAN`, mounted by Compose from `tracking-plan/plan.json`), it marks each new stored event `planned`, `unplanned_event`, or `invalid_properties`. It never rejects or drops an event for plan reasons; the SDK and the ACK are unchanged. The contract adds an optional `planStatus` on received events, a `planStatus` filter, and `GET /v1/event-counts/by-plan-status` (compatible, contract version 1). The Dashboard shows a status badge, a filter, and a violations card. Migration `V2__add_plan_status.sql` adds a nullable column; events stored before it have no status ([ADR-0022](docs/adr/0022-server-tracking-plan-status.md)).
- Public API check for the Android SDK. `sdk/gradlew -p sdk :sdk-android:apiCheck` fails when the public API of `sdk-android` differs from the committed dump `sdk/sdk-android/api/sdk-android.api`. `scripts/verify-contract.sh` runs it ([ADR-0019](docs/adr/0019-sdk-public-api-check.md)).
- Release workflow: pushing a tag `vX.Y.Z` runs `.github/workflows/release.yml`, which checks the tag against `sdkVersion`, builds the Maven ZIP and checksum with `scripts/release.sh`, and creates the GitHub Release with the notes from `docs/releases/vX.Y.Z.md`. It never overwrites a release. `scripts/release.sh <version> --check` only compares the version. Steps: `docs/releases/README.md`. Dependency updates: see "Renovate" under "Changed" ([ADR-0024](docs/adr/0024-release-workflow-and-dependency-updates.md)).
- Server readiness check. `GET /ready` runs `SELECT 1` and answers 503 with `Retry-After: 1` when the database is unavailable. `/health` stays a liveness check. Compose gives `server` a health check on `/ready` and gives `server` and `db` `restart: unless-stopped`. `/ready` is not part of the contract ([ADR-0026](docs/adr/0026-server-readiness-and-restart.md), PR #110).
- CodeQL advanced setup (`.github/workflows/codeql.yml`, manual Kotlin build) replaces the default setup. PR #158 cleared the first alerts ([ADR-0032](docs/adr/0032-codeql-advanced-setup.md), PR #148).
- Gradle wrapper validation: the `gradle-wrapper` job in `.github/workflows/repo-rules.yml` checks every `gradle-wrapper.jar` against the published checksums (PR #146).
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

[Unreleased]: https://github.com/HyeokjaeChoi/signaldock/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/HyeokjaeChoi/signaldock/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/HyeokjaeChoi/signaldock/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/HyeokjaeChoi/signaldock/releases/tag/v0.1.0
