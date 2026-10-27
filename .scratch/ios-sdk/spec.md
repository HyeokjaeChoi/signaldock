# SignalDock iOS SDK spec

Status: design agreed with the owner on 2026-10-26 and 2026-10-27 (grilling Q1 to Q14, log in `.scratch/ios-sdk/decisions.md`). Research notes with primary sources are in `.scratch/next-scope/`. No product code exists yet. Tickets are in `.scratch/ios-sdk/issues/`.

## 1. Goal and scope

Add a native iOS SDK with the same behavior as Android SDK 0.3.0, and let the server and the Dashboard tell events from the two platforms apart.

- In scope: the iOS SDK (`sdk-ios/`), the contract field `sdkPlatform` and the server, Dashboard, and Android SDK changes it needs, Swift code generation, an iOS consumer and demo app, iOS CI gates, a Simulator durability script, size and host-cost measurement on the Simulator, a size budget gate, a GitHub Release ZIP for iOS.
- Out of scope (deferred, not rejected): remote SwiftPM and Maven Central (owner: do both together later), CocoaPods, XCFramework binaries, app extensions, Objective-C or completion-handler APIs, BGTaskScheduler, measurement on a physical iPhone, connecting the iOS app to the cloud demo, Flutter / React Native / Unity / WebView bridges (Q150 stays).
- The exclusions of `.scratch/signaldock/spec.md` section 1 still apply, except "iOS SDK", which this spec replaces.

## 2. Decisions

ADR-0046 records D2 to D5. ADR-0047 records D1 and D6 to D15.

| ID | Decision | Rejected alternatives (reason) |
| --- | --- | --- |
| D1 | Behavior parity with Android SDK 0.3.0: `initialize`, `track` with a builder, `setOptOut`, `clearQueue`, `diagnostics`, contract deprecation warning (ADR-0030), client reports (ADR-0036), HTTPS only (ADR-0035), validation, queue limits, ACK, backoff, hold, 413 split (ADR-0003, ADR-0006). Android-only mechanisms (WorkManager, R8, Baseline Profile) are not ported. | MVP subset first (behavior would change between iOS versions; shared fixtures could not be used) |
| D2 | Optional event field `sdkPlatform`, enum `android`, `ios`, next to `sdkVersion` in `EventIngest` and in the client report body. Contract 1 additive (ADR-0002): the server deploys first. Query responses (event list, event detail, client report count items) carry it as required. | Prefix in `sdkVersion` such as `ios/0.1.0` (mixes two meanings in one field); HTTP header (not stored per event) |
| D3 | A missing `sdkPlatform` is stored as `android`. Only Android SDK 0.1.0 to 0.3.0 omit it. From now on every SDK must send it; SDK wire fixture tests enforce this. Migration V4 adds `sdk_platform` to `events` and `client_reports` and fills old rows with `android`. | `unknown` (old Android events would drop out of the `android` filter and need an odd kill-switch key; the response enum would need a value that no SDK sends) |
| D4 | Kill switch (ADR-0037) blocks by the pair (platform, version). `SIGNALDOCK_BLOCKED_SDK_VERSIONS` items are `platform:version`, for example `ios:0.1.0`. An item with no prefix means `android`, so current settings keep their meaning. | Version only (blocking iOS 0.1.0 would also block a future Android 0.1.0 event) |
| D5 | Query filter `sdkPlatform` (`android` or `ios`) on five endpoints: the four that take `installationId` (`/v1/events`, `/v1/event-counts/by-name`, `by-time`, `by-plan-status`) and `/v1/client-report-counts`, whose counts group by (platform, version). Strict parameters (ADR-0007): the server deploys before the Dashboard. The Dashboard shows the platform in the list and the detail and adds it to the shared filter set. | Display only (owner asked for the filter) |
| D6 | Installation ID: UUIDv4 in a file in Application Support `signaldock/`, `isExcludedFromBackup = true`. Deleting the app deletes it, so a reinstall gets a new ID (ADR-0001). Known limit: Apple says the flag does not guarantee that the file never appears on a restored device, so a restored device can rarely share an ID. File class `completeUntilFirstUserAuthentication`, set explicitly (ADR-0047 amendment, issue 20). | Keychain (often survives app deletion: against ADR-0001 and privacy); `identifierForVendor` (Apple-controlled lifetime, shared across the vendor's apps, not UUIDv4) |
| D7 | Queue: system SQLite3 through its C API behind a small internal wrapper. Zero external dependencies. Same queue schema and transaction boundaries as the Android Room queue (ACK applied in one transaction). Versioned SQL migrations; a released migration is frozen (iOS form of ADR-0025). File class `completeUntilFirstUserAuthentication` on the database and its `-wal`, `-shm`, and `-journal` files; open the database with `SQLITE_OPEN_FILEPROTECTION_COMPLETEUNTILFIRSTUSERAUTHENTICATION`. An open failure before the first unlock makes `initialize()` return `InitError.StorageFailure` with no instance (ADR-0003 item 6, ADR-0017); events in that window are dropped, and the host calls `initialize()` again later. | GRDB (external dependency and version clashes with the host); Core Data or SwiftData (less control of transaction boundaries; SwiftData needs iOS 17); files (4 of 5 surveyed SDKs use files, but per-event ACK, byte limits, and same-ID groups would need a custom index) |
| D8 | Delivery: Android foreground rules (drain after 20 stored events or 10 s after the first; the persisted next-attempt time is the only retry clock). Plus one drain when the app enters the background, inside `beginBackgroundTask` (start it before the work; end it once; the expiration handler cancels the request, and the queue and the ACK transaction keep the data). Plus a drain at launch and at return to the foreground when the stored next-attempt time has passed. Platform difference: no periodic background send; events left after a background kill go at the next launch. | BGTaskScheduler (host must add `Info.plist` identifiers and a capability; OS-chosen timing; none of 6 surveyed SDKs use it) |
| D9 | iOS 15 or later. Swift 6 language mode (strict concurrency). Public API is `async`, one to one with the Kotlin `suspend` API; results are Swift `enum`s; public types are `Sendable`. Swift Concurrency only, no third-party async library. The sender is an actor. SQLite runs only on an actor whose custom `SerialExecutor` (SE-0392, in the iOS 15 form `enqueue(_: UnownedJob)` with `UnownedJob.runSynchronously(on:)`; `ExecutorJob` needs iOS 17) uses a dedicated serial queue, so blocking calls stay off the cooperative pool. Code re-checks actor state after every `await` (SE-0306). `@unchecked Sendable` needs an ADR that names its lock or queue invariant. Public entry points are `@available(iOSApplicationExtension, unavailable)`: first release is for apps only. | iOS 13 (needs the concurrency back-deploy runtime); completion handlers and Objective-C API (add when asked) |
| D10 | Wire DTOs: the pinned openapi-generator `swift6`, models only, `internal`, written to `generated/swift`; the existing drift check covers it. The output must use Foundation `Codable` only. Fallback, decided in its ticket and recorded in an ADR: hand-written `Codable` types checked by the same fixtures, if the output needs a runtime library or gets the `PropertyValue` oneOf wrong. iOS tests read the shared `fixtures/validation`. Wire corpus: each SDK has its own byte-locked corpus, `fixtures/sdk-wire/android/` and `fixtures/sdk-wire/ios/`, with the same case names. The server corpus test parses both folders. A comparison test checks that cases with the same name have equal JSON values (numbers compared by value; `sdkPlatform` and `sdkVersion` excluded). Byte equality between the SDKs is not a goal: number spelling differs (for example Kotlin writes `1.0E-7`) and the SDK fields differ (owner decision 2026-10-27). The tracking plan generator (ADR-0021) gets a Swift output. | Apple swift-openapi-generator (two runtime packages, against zero dependencies) |
| D11 | Distribution like Android (ADR-0009, ADR-0024, ADR-0039): source in the new top-level `sdk-ios/`; its own version line from `0.1.0`; a tag `ios-vX.Y.Z` runs an iOS release workflow that publishes a Swift package source ZIP (with `Package.swift`), a SHA-256 file, and a provenance attestation. The host adds the unzipped folder as a local package. No library evolution build is shipped. | Remote SwiftPM from this repository (SwiftPM reads version tags only as plain semver, and `v*.*.*` tags belong to Android); separate iOS repository (outward-facing; deferred with Maven Central) |
| D12 | One SwiftUI app `compatibility/consumer-ios` is both the consumer check and the demo. It uses the SDK only from the unzipped release ZIP. Screens: product view, add to cart, simulated purchase, with the tracking plan Swift output. It sends to the local Compose server. CI builds it in Debug and Release (`-O`, whole module, dead stripping: the iOS form of the host release-build check of `consumer-android`). | Two apps like Android (iOS has no R8 step that needs a separate release consumer) |
| D13 | CI gates: (1) `ios-test` on a macOS runner: Simulator build and unit tests under Thread Sanitizer; (2) a separate ASan and UBSan test run; (3) `swift format` strict with documentation required on public declarations; (4) warnings as errors in CI only; (5) `xcodebuild analyze`; (6) CodeQL adds Swift (ADR-0032); (7) public API check: a `.swiftinterface` from a check-only build, committed in `sdk-ios/api/` and diffed; (8) `scripts/check-repo-rules.sh`: no external `.package(` in `sdk-ios/Package.swift`, no `privacy: .public` in `sdk-ios`, required-reason API use must match the privacy manifest, released SQL migrations are frozen; the release workflow checks the iOS version constant against the `ios-v` tag; (9) consumer app Debug and Release build; (10) size budget (D15). Xcode steps in `scripts/verify-contract.sh` run locally only when `xcodebuild` exists, else they print a skip line; CI always runs them. Tests are deterministic: injected clock and transport, no sleeps. | Test retry (fix flaky tests at the cause, like PR #203); coverage floor (1 of 9 vendors); other Apple platforms (out of scope) |
| D14 | Privacy manifest `PrivacyInfo.xcprivacy` as a package resource: `NSPrivacyTracking = false`, no tracking domains; collected data `DeviceID` (installation ID) and `ProductInteraction` (business events), both not linked, not tracking, purpose Analytics. Required-reason APIs: none planned. The SDK does not use `systemUptime`, `mach_absolute_time`, disk-space APIs, `UserDefaults`, or file timestamps; if one becomes necessary, the manifest and the D13 check change in the same PR. The integration guide tells hosts to declare any other data type they put in event properties. | No manifest (Apple rejects uploads that use required-reason APIs without a reason since 2024-05; 8 of 9 vendors ship one) |
| D15 | Verification on the Simulator: `scripts/simulator-durability.sh` (local only, `xcrun simctl`) with kill during store and during send, restart, update install, uninstall and install (new installation ID), opt-out, offline; pass means no lost event, no server duplicate, queue state as expected (ADR-0011, ADR-0029). Measurement: SDK size (consumer Release arm64 with and without the SDK, unsigned) and host cost (`initialize()` and `track()` main-thread time, cold start, memory). Then a size budget gate from that measurement (ADR-0020 then ADR-0023 order). | Physical iPhone now (needs the owner's device, an Apple Developer account, and signing; like ADR-0042) |

## 3. Components

| Path | Content |
| --- | --- |
| `openapi/contract-v1.yaml` | `sdkPlatform` on `EventIngest`, client report, event list and detail responses; `SdkPlatformFilter` parameter |
| `server/` | V4 migration, ingest default, kill-switch pair, filters, client report grouping |
| `dashboard/src` | Platform display and filter |
| `sdk/sdk-android` | 0.4.0 sends `sdkPlatform: "android"` |
| `generated/swift` | openapi-generator `swift6` models |
| `tracking-plan/generate.mjs` | Swift output |
| `sdk-ios/` | Swift package `SignalDock` (product and module name), sources, tests, `api/`, `PrivacyInfo.xcprivacy`, SQL migrations |
| `compatibility/consumer-ios/` | SwiftUI consumer and demo app |
| `scripts/` | `simulator-durability.sh`, iOS measurement script, iOS ZIP build, new checks in `check-repo-rules.sh` and `verify-contract.sh` |
| `.github/workflows/` | iOS jobs in `repo-rules.yml`, `release-ios.yml` on `ios-v*.*.*`, CodeQL Swift |

## 4. Order and release rules

1. Contract, then server, then Dashboard. The Android SDK 0.4.0 and the iOS SDK 0.1.0 are released only after the server with `sdkPlatform` support is deployed, including the cloud demo server when a released SDK points at it. An older server rejects the field with `UNKNOWN_FIELD`, and the SDK would hold its queue.
2. iOS SDK work can run in parallel with the server and Dashboard tickets. Its runs against the Compose server (delivery, client reports, consumer app) wait for the server ticket, and its release waits for the deploy.
3. Pushing a release tag is outward-facing: the owner approves each tag.

## 5. Known limits and platform differences

- A restored device can rarely share an installation ID (D6).
- No periodic background send on iOS (D8).
- Simulator cannot reproduce backup restore or a full disk; `simulator-durability.sh` lists them as not run.
- Simulator numbers are not device numbers (like emulator numbers, ADR-0014).
- The GitHub macOS runner may not have an iOS 15 Simulator runtime. The CI ticket checks it; fallback: build at deployment target 15 and test on the oldest runtime the runner has, and state it in the README.
- Apple's signature check for SDKs is stated for binary dependencies; a source ZIP is not signed.

## 6. Completion criteria

- All tickets in `.scratch/ios-sdk/issues/` are resolved with merged PRs.
- `ios-v0.1.0` and Android `v0.4.0` are on GitHub Releases with SHA-256 files and verified attestations.
- From a clean clone, the README steps build the iOS ZIP, add it to `consumer-ios`, send events to the local stack, and show them in the Dashboard with platform `ios` and the platform filter.
- `simulator-durability.sh` results and the measurement results are committed with environment records, as for Android.

## 7. Revisit when

- A host asks for app extension support, Objective-C, or completion handlers (D9).
- Maven Central and remote SwiftPM start (D11).
- The owner can lend a physical iPhone and a developer account (D15).
- A required-reason API becomes necessary (D14).
