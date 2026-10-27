---
status: accepted
---

# iOS SDK design (iOS SDK spec D1 and D6 to D15)

On 2026-10-26 and 2026-10-27 UTC the owner agreed to add a native iOS SDK (`.scratch/ios-sdk/spec.md`, log in `.scratch/ios-sdk/decisions.md`, Q1 to Q14). The wire field is in [ADR-0046](0046-sdk-platform-field.md). This ADR records the iOS SDK. Research with primary sources is in `.scratch/next-scope/`.

## Context

- The Android SDK `0.3.0` is the reference. The decisions below mirror the Android ADRs unless a platform difference is stated.
- No iOS code exists. Everything in this ADR is a design.
- Out of scope and deferred, not rejected: remote SwiftPM and Maven Central (the owner wants both together later), CocoaPods, XCFramework, app extensions, Objective-C and completion-handler APIs, `BGTaskScheduler`, a physical iPhone, the cloud demo, and Flutter, React Native, Unity, and WebView bridges (Q150 stays).

## Decision

### D1. Parity with Android SDK 0.3.0

The first release has the same behavior: `initialize`, `track` with a builder, `setOptOut`, `clearQueue`, `diagnostics`, the contract deprecation warning, client reports, HTTPS only, validation, queue limits, ACK, backoff, hold, and the 413 split. Android-only mechanisms (WorkManager, R8, Baseline Profile) are not ported.
Mirrors: [ADR-0003](0003-sdk-storage-decisions.md), [ADR-0006](0006-sdk-delivery-decisions.md), [ADR-0030](0030-sdk-contract-deprecation-warning.md), [ADR-0034](0034-sdk-opt-out.md), [ADR-0035](0035-sdk-https-enforcement.md), [ADR-0036](0036-sdk-client-reports.md).
Rejected: an MVP subset first. Behavior would change between iOS versions, and the shared fixtures could not be used.

### D6. Installation ID

A UUIDv4 in a file in Application Support `signaldock/`, with `isExcludedFromBackup = true`. Deleting the app deletes the file, so a reinstall gets a new ID.
Mirrors: [ADR-0001](0001-installation-scoped-identity.md) (new ID after reinstall and after a supported restore).
Rejected: Keychain (it often survives app deletion, against ADR-0001 and privacy); `identifierForVendor` (Apple controls its lifetime, it is shared across apps of the vendor, and it is not UUIDv4).
Known limit: Apple says the flag does not guarantee that the file never appears on a restored device. A restored device can rarely share an ID (source: https://developer.apple.com/documentation/foundation/urlresourcevalues/isexcludedfrombackup).

### D7. Queue

The system SQLite3 C API behind a small internal wrapper. No external dependency. The queue schema and the transaction boundaries are the same as the Android Room queue. An ACK is applied in one transaction. SQL migrations are versioned, and a released migration is frozen.
Mirrors: [ADR-0003](0003-sdk-storage-decisions.md), [ADR-0006](0006-sdk-delivery-decisions.md), [ADR-0025](0025-sdk-clock-jump.md) (frozen released schema, checked by `scripts/check-repo-rules.sh`).
Rejected: GRDB (external dependency, version clashes with the host); Core Data and SwiftData (less control of transactions, and SwiftData needs iOS 17); plain files (four of five surveyed SDKs use them, but per-event ACK, byte limits, and same-ID groups would need a custom index).

### D8. Delivery

Android foreground rules: drain after 20 stored events, or 10 s after the first. The persisted next-attempt time is the only retry clock. In addition:

- One drain when the app enters the background, inside `beginBackgroundTask`. Start the task before the work. End it once. The expiration handler cancels the request. The queue and the ACK transaction keep the data.
- One drain at launch and at return to the foreground, when the stored next-attempt time has passed.

Platform difference: there is no periodic background send. Events left after a background kill go at the next launch.
Mirrors: [ADR-0006](0006-sdk-delivery-decisions.md), [ADR-0011](0011-sdk-device-test-harness.md) and [ADR-0029](0029-device-fault-tests-and-power-loss.md) (loss criteria).
Rejected: `BGTaskScheduler` (the host must add `Info.plist` identifiers and a capability; the OS chooses the time; none of six surveyed SDKs use it).
Source: https://developer.apple.com/documentation/uikit/uiapplication/beginbackgroundtask(withname:expirationhandler:

### D9. Language level and concurrency

- iOS 15 or later. Swift 6 language mode with strict concurrency.
- The public API is `async`, one to one with the Kotlin `suspend` API. Results are Swift `enum` values. Public types are `Sendable`.
- Swift Concurrency only. No third-party async library.
- The sender is an `actor`. SQLite runs only on an actor whose custom `SerialExecutor` (SE-0392) uses a dedicated serial queue, so blocking calls stay off the cooperative pool. On iOS 15 the executor implements `enqueue(_ job: UnownedJob)` and calls `UnownedJob.runSynchronously(on:)`; `ExecutorJob` and `enqueue(_: consuming ExecutorJob)` need iOS 17 and are not used. Check (2026-10-27, Xcode 27.1, Swift 6.4): this form type-checks for `arm64-apple-ios15.0-simulator` in Swift 6 mode with `-warnings-as-errors`; the `ExecutorJob` form fails with "only available in iOS 17.0 or newer". Gap: run time on an iOS 15 runtime is not verified (ticket 07).
- Code checks actor state again after every `await` (SE-0306).
- `@unchecked Sendable` needs an ADR that names its lock or queue invariant.
- Public entry points are `@available(iOSApplicationExtension, unavailable)`. The first release is for apps only.

Mirrors: [ADR-0017](0017-sdk-crash-safety.md) (public API does not throw to the host), [ADR-0034](0034-sdk-opt-out.md) (result types).
Rejected: iOS 13 (it needs the concurrency back-deploy runtime); completion handlers and Objective-C API (add when a host asks).

### D10. Wire types and fixtures

Wire DTOs come from the pinned openapi-generator `swift6`: models only, `internal`, written to `generated/swift`. The existing drift check covers it. The output must use Foundation `Codable` only. Fallback, decided in its ticket and recorded in an ADR: hand-written `Codable` types checked by the same fixtures, when the output needs a runtime library or gets the `PropertyValue` `oneOf` wrong. iOS tests read the shared `fixtures/validation`. Wire corpus: each SDK has its own byte-locked corpus, `fixtures/sdk-wire/android/` and `fixtures/sdk-wire/ios/`, with the same case names. The server corpus test parses both folders. A comparison test checks that cases with the same name have equal JSON values (numbers compared by value; `sdkPlatform` and `sdkVersion` excluded). Byte equality between the SDKs is not a goal: number spelling differs (for example Kotlin writes `1.0E-7`) and the SDK fields differ (owner decision 2026-10-27). The tracking plan generator gets a Swift output.
Mirrors: [ADR-0002](0002-openapi-json-contract.md), [ADR-0021](0021-tracking-plan-codegen.md), [ADR-0027](0027-request-400-contract-only.md).
Rejected: Apple swift-openapi-generator (two runtime packages, against zero dependencies).

### D11. Distribution

Source in the new top-level `sdk-ios/`. Its own version line from `0.1.0`. A tag `ios-vX.Y.Z` runs an iOS release workflow. It publishes a Swift package source ZIP (with `Package.swift`), a SHA-256 file, and a provenance attestation. The host adds the unzipped folder as a local package. No library evolution build is shipped.
Mirrors: [ADR-0009](0009-maven-zip-and-consumer-app.md), [ADR-0024](0024-release-workflow-and-dependency-updates.md), [ADR-0039](0039-release-provenance-attestation.md).
Rejected: remote SwiftPM from this repository (SwiftPM reads version tags as plain semver, and the `v*.*.*` tags belong to Android); a separate iOS repository (outward-facing; deferred with Maven Central).

### D12. Consumer and demo app

One SwiftUI app, `compatibility/consumer-ios`, is both the consumer check and the demo. It uses the SDK only from the unzipped release ZIP. Screens: product view, add to cart, simulated purchase, with the tracking plan Swift output. It sends to the local Compose server. CI builds it in Debug and Release (`-O`, whole module, dead stripping). This is the iOS form of the host release-build check of `consumer-android`.
Mirrors: [ADR-0009](0009-maven-zip-and-consumer-app.md), [ADR-0018](0018-sdk-release-r8-measurement.md).
Rejected: two apps like Android (iOS has no R8 step that needs a separate release consumer).

### D13. CI gates

1. `ios-test` on a macOS runner: Simulator build and unit tests under Thread Sanitizer.
2. A separate ASan and UBSan test run.
3. `swift format` strict, with documentation required on public declarations.
4. Warnings as errors in CI only.
5. `xcodebuild analyze`.
6. CodeQL adds Swift.
7. Public API check: a `.swiftinterface` from a check-only build, committed in `sdk-ios/api/` and diffed.
8. New checks in `scripts/check-repo-rules.sh`: no external `.package(` in `sdk-ios/Package.swift`; no `privacy: .public` in `sdk-ios`; required-reason API use matches the privacy manifest; released SQL migrations are frozen. The iOS version has one source, a constant in `sdk-ios`; the iOS release workflow checks it against the `ios-v` tag.
9. Consumer app Debug and Release build.
10. Size budget (D15).

Xcode steps in `scripts/verify-contract.sh` run locally only when `xcodebuild` exists. Otherwise they print a skip line. CI always runs them. Tests are deterministic: injected clock and transport, no sleeps.
Mirrors: [ADR-0019](0019-sdk-public-api-check.md) (item 7), [ADR-0023](0023-ci-device-tests-and-size-budget.md) (item 10), [ADR-0032](0032-codeql-advanced-setup.md) (item 6), [ADR-0033](0033-sdk-dependency-baseline.md) (zero dependencies, item 8), [ADR-0025](0025-sdk-clock-jump.md) (frozen migrations).
Rejected: test retry (fix flaky tests at the cause); a coverage floor (one of nine surveyed vendors); other Apple platforms (out of scope).

### D14. Privacy manifest

`PrivacyInfo.xcprivacy` is a package resource. `NSPrivacyTracking = false`, no tracking domains. Collected data: `DeviceID` (installation ID) and `ProductInteraction` (business events). Both are not linked, not tracking, purpose Analytics. Required-reason APIs: none planned. The SDK does not use `systemUptime`, `mach_absolute_time`, disk-space APIs, `UserDefaults`, or file timestamps. If one becomes necessary, the manifest and the D13 check change in the same PR. The integration guide tells hosts to declare any other data type that they put in event properties.
Mirrors: [ADR-0034](0034-sdk-opt-out.md) (what the SDK collects), [ADR-0001](0001-installation-scoped-identity.md).
Rejected: no manifest (Apple rejects uploads that use a required-reason API without a reason; 8 of 9 surveyed vendors ship one).
Source: https://developer.apple.com/documentation/bundleresources/privacy-manifest-files and https://developer.apple.com/documentation/bundleresources/describing-use-of-required-reason-api

### D15. Verification on the Simulator

`scripts/simulator-durability.sh` (local only, `xcrun simctl`) covers: kill during store and during send, restart, update install, uninstall and install (new installation ID), opt-out, and offline. Pass means no lost event, no server duplicate, and the expected queue state. Measurement: SDK size (consumer Release arm64 with and without the SDK, unsigned) and host cost (`initialize()` and `track()` main-thread time, cold start, memory). The size budget gate follows from that measurement.
Mirrors: [ADR-0011](0011-sdk-device-test-harness.md), [ADR-0029](0029-device-fault-tests-and-power-loss.md), [ADR-0020](0020-sdk-host-app-cost.md) then [ADR-0023](0023-ci-device-tests-and-size-budget.md) (measure first, gate second), [ADR-0014](0014-sdk-device-measurement-harness.md).
Rejected: a physical iPhone now (it needs the owner device, an Apple Developer account, and signing; like [ADR-0042](0042-measurement-on-a-physical-device.md)).

## iOS 15 Simulator fallback (ticket 05, 2026-10-28)

- The `macos-26` runner image (version 20261013.0424.1, Xcode 26.6 default) lists these iOS Simulator runtimes: 26.2, 26.4, and 26.5. The `macos-27` Xcode 27 image lists SDKs for iOS 27.0 to 27.2 and has no iOS 15 runtime either (sources: `images/macos/macos-26-arm64-Readme.md` and `images/macos/xcode-27-arm64-Readme.md` in `actions/runner-images`). The first CI run of each job must confirm this: `scripts/verify-ios.sh` prints `xcrun simctl list runtimes`.
- Decision: CI uses `macos-26`. The package builds at deployment target iOS 15 (`Package.swift`). Tests and the sanitizer runs execute on the oldest installed runtime, which `scripts/verify-ios.sh` selects (iOS 26.2 on that image; iOS 27.0 on the local Mac of 2026-10-28). The script prints a note when the chosen runtime is not iOS 15.
- Gap: no test runs on an iOS 15 runtime, so the iOS 15 form of the executor (D9) and iOS 15 behavior are not run-tested. A local iOS 15 runtime or a physical device (D15) would close it. The README (ticket 18) states this.
- UBSan: `-enableUndefinedBehaviorSanitizer` is on in the ASan run. It instruments C-family code only, and the package has no C target. It starts to matter if one is added.
- Analyze: `xcodebuild analyze` runs the Clang static analyzer, which checks C-family code only. For this Swift-only package the analyze step is a build, like the UBSan note. It starts to matter with a C target.
- Xcode: the `ios-test`, `ios-sanitize`, and CodeQL Swift jobs run `sudo xcode-select -s /Applications/Xcode_26.6.app`, the default Xcode of the `macos-26` image above, so a change of the runner default does not change the toolchain silently. Change the version in the three jobs together.

## Known limits

- A restored device can rarely share an installation ID (D6).
- No periodic background send on iOS (D8).
- The Simulator cannot reproduce a backup restore or a full disk. The script lists them as not run.
- Simulator numbers are not device numbers (like emulator numbers, ADR-0014).
- The GitHub macOS runner has no iOS 15 Simulator runtime (ticket 05, 2026-10-28; see "iOS 15 Simulator fallback"). Fallback: build at deployment target 15, test on the oldest runtime the runner has, and say so in the README.
- Apple states the signature check for SDKs for binary dependencies. A source ZIP is not signed.

## Not verified here

Everything. No iOS code exists yet. Tickets must confirm these facts before they rely on them:

- Run time on an iOS 15 Simulator runtime (none exists on the CI runner; see "iOS 15 Simulator fallback").
- The `swift6` generator output uses Foundation only and handles `PropertyValue` correctly.
- The exact SwiftPM resource form that packages `PrivacyInfo.xcprivacy` (the Apple page does not state it).
- Device-to-device transfer does not carry files that are excluded from backup.

## Revisit when

- A host asks for app extensions, Objective-C, or completion handlers (D9).
- Maven Central and remote SwiftPM start (D11).
- The owner can lend a physical iPhone and a developer account (D15).
- A required-reason API becomes necessary (D14).

## Amendment (2026-10-28, issue 20)

D6 and D7 did not name a Data Protection class. The owner decided on 2026-10-28:

- The SDK sets the class `completeUntilFirstUserAuthentication` explicitly on the installation ID file and on the SQLite queue files: the database file and the side files that SQLite makes and removes by itself (`-wal`, `-shm`, `-journal`). The class must cover files that SQLite creates later, so the SDK opens the database with the Apple SQLite open flag `SQLITE_OPEN_FILEPROTECTION_COMPLETEUNTILFIRSTUSERAUTHENTICATION` (`sqlite3_open_v2`). It may also set the class on the `signaldock/` directory; a class that the SDK sets on a side file after SQLite made it does not count, because the next file of the same name does not get it.
- This is also the iOS default for third-party app files. The SDK states it so that a change of the OS default does not change the SDK behavior.
- Before the first unlock after a restart, the system does not let the app open a file of this class. `initialize()` opens both files, as on Android (ADR-0003 item 6, ADR-0017, D9 parity). If it cannot open them for this reason, it returns the storage-failure init error (`InitError.StorageFailure`) and no instance: no throw into the host, and `track()` and `diagnostics()` cannot be called. Events that the host tracks in this window are dropped, as ADR-0017 says. The SDK does not open files lazily and does not retry by itself. After the first unlock, the host calls `initialize()` again (the integration guide row for `StorageFailure`). The iOS integration notes tell host developers this.
- Rejected: a stricter class (a launch by the system before the first unlock would lose more events, and the queue holds business events, not secrets); keeping the OS default without stating it.

Sources (read 2026-10-28):

- [FileProtectionType.completeUntilFirstUserAuthentication](https://developer.apple.com/documentation/foundation/fileprotectiontype/completeuntilfirstuserauthentication): the file cannot be accessed until the device has booted and the user has unlocked it for the first time. Then the app can access it, also while the device is locked.
- [Apple Platform Security, Data Protection classes](https://support.apple.com/guide/security/data-protection-classes-secb010e978a/web): this class is "the default class for all third-party app data not otherwise assigned to a Data Protection class".
- `sqlite3.h` in the Apple iOS SDK (Xcode, `iPhoneOS.sdk/usr/include/sqlite3.h`) defines `SQLITE_OPEN_FILEPROTECTION_COMPLETEUNTILFIRSTUSERAUTHENTICATION`.

Not verified here: the exact error code that SQLite returns for this open failure, and whether new files inherit the class from the directory (Apple primary docs found do not say). Ticket 07 checks both on a Simulator or device, and tests that a `-wal` or `-journal` file that SQLite made again after a close has the class.
