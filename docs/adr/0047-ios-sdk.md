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
- The public API is `async`, one to one with the Kotlin `suspend` API. Kotlin non-suspend functions map to synchronous Swift functions. Results are Swift `enum` values. Public types are `Sendable`.
- Swift Concurrency only. No third-party async library.
- The sender is an `actor`. SQLite runs only on an actor whose custom `SerialExecutor` (SE-0392) uses a dedicated serial queue, so blocking calls stay off the cooperative pool. On iOS 15 the executor implements `enqueue(_ job: UnownedJob)` and calls `UnownedJob.runSynchronously(on:)`; `ExecutorJob` and `enqueue(_: consuming ExecutorJob)` need iOS 17 and are not used. Check (2026-10-27, Xcode 27.1, Swift 6.4): this form type-checks for `arm64-apple-ios15.0-simulator` in Swift 6 mode with `-warnings-as-errors`; the `ExecutorJob` form fails with "only available in iOS 17.0 or newer". Gap: run time on an iOS 15 runtime is not verified (ticket 07).
- Code checks actor state again after every `await` (SE-0306).
- `@unchecked Sendable` needs an ADR that names its lock or queue invariant.
- Public entry points are `@available(iOSApplicationExtension, unavailable)`. The first release is for apps only.

Mirrors: [ADR-0017](0017-sdk-crash-safety.md) (public API does not throw to the host), [ADR-0034](0034-sdk-opt-out.md) (result types).
Rejected: iOS 13 (it needs the concurrency back-deploy runtime); completion handlers and Objective-C API (add when a host asks).

### D10. Wire types and fixtures

Wire DTOs come from the pinned openapi-generator `swift6`: models only, `internal`, written to `sdk-ios/Sources/SignalDock/Generated` (first text: `generated/swift`; changed in the ticket 06 amendment below). The drift check covers it. The output must use Foundation `Codable` only. Fallback, decided in its ticket and recorded in an ADR: hand-written `Codable` types checked by the same fixtures, when the output needs a runtime library or gets the `PropertyValue` `oneOf` wrong. iOS tests read the shared `fixtures/validation`. Wire corpus: each SDK has its own byte-locked corpus, `fixtures/sdk-wire/android/` and `fixtures/sdk-wire/ios/`, with the same case names. The server corpus test parses both folders. A comparison test checks that cases with the same name have equal JSON values (numbers compared by value; `sdkPlatform` and `sdkVersion` excluded). Byte equality between the SDKs is not a goal: number spelling differs (for example Kotlin writes `1.0E-7`) and the SDK fields differ (owner decision 2026-10-27). The tracking plan generator gets a Swift output.
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

`PrivacyInfo.xcprivacy` is a package resource, declared as `resources: [.process("PrivacyInfo.xcprivacy")]` on the `SignalDock` target (Apple: a resource that Xcode does not detect by default must be declared in the package manifest, https://developer.apple.com/documentation/xcode/bundling-resources-with-a-swift-package). `NSPrivacyTracking = false`, no tracking domains. Collected data: `DeviceID` (installation ID) and `ProductInteraction` (business events). Both are not linked, not tracking, purpose Analytics. Required-reason APIs: none planned. The SDK does not use `systemUptime`, `mach_absolute_time`, disk-space APIs, `UserDefaults`, or file timestamps. If one becomes necessary, the manifest and the D13 check change in the same PR. The integration guide tells hosts to declare any other data type that they put in event properties.
Mirrors: [ADR-0034](0034-sdk-opt-out.md) (what the SDK collects), [ADR-0001](0001-installation-scoped-identity.md).
Rejected: no manifest (Apple rejects uploads that use a required-reason API without a reason; 8 of 9 surveyed vendors ship one).
Source: https://developer.apple.com/documentation/bundleresources/privacy-manifest-files and https://developer.apple.com/documentation/bundleresources/describing-use-of-required-reason-api

### D15. Verification on the Simulator

`scripts/simulator-durability.sh` (local only, `xcrun simctl`) covers: kill during store and during send, restart, update install, uninstall and install (new installation ID), opt-out, and offline. Pass means no lost event, no server duplicate, and the expected queue state. Measurement: SDK size (consumer Release arm64 with and without the SDK, unsigned) and host cost (`initialize()` and `track()` main-thread time, cold start, memory). The size budget gate follows from that measurement.
Mirrors: [ADR-0011](0011-sdk-device-test-harness.md), [ADR-0029](0029-device-fault-tests-and-power-loss.md), [ADR-0020](0020-sdk-host-app-cost.md) then [ADR-0023](0023-ci-device-tests-and-size-budget.md) (measure first, gate second), [ADR-0014](0014-sdk-device-measurement-harness.md).
Rejected: a physical iPhone now (it needs the owner device, an Apple Developer account, and signing; like [ADR-0042](0042-measurement-on-a-physical-device.md)).

## iOS 15 Simulator fallback (ticket 05, 2026-10-28)

- The first CI run on PR #245 ([repo-rules run 40537476359](https://github.com/HyeokjaeChoi/signaldock/actions/runs/40537476359), head 06e526b) confirmed it. The `macos-26` runner (Xcode 26.6) lists these iOS Simulator runtimes: iOS 26.2 (23C54), iOS 26.4 (26.4.1 - 23E254a), and iOS 26.5 (23F77). It has no iOS 15 runtime. The `ios-test` job log shows "simulator runtime: iOS 26.2 (oldest installed)" ([ios-test](https://github.com/HyeokjaeChoi/signaldock/actions/runs/40537476359/job/122306522044), [ios-sanitize](https://github.com/HyeokjaeChoi/signaldock/actions/runs/40537476359/job/122306521753); both passed). Before the run, the runner-images readmes (`images/macos/macos-26-arm64-Readme.md`, image 20261013.0424.1) listed the same runtimes, and `images/macos/xcode-27-arm64-Readme.md` lists SDKs for iOS 27.0 to 27.2 and no iOS 15 runtime either.
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

## Ticket 07 results (2026-10-28, storage)

Code: `sdk-ios/Sources/SignalDock/Internal/Storage/`. Facts below come from runs on the iOS 27.0 Simulator (Xcode 27.1, Swift 6.4), the only runtime installed on that Mac.

Built as the design sources say:

- The queue schema is the Android Room schema 3 (tables `events`, `rejections`, `retry_state`, `dropped_counts`), in one migration file `V1__initial.sql`, because iOS 0.1.0 is not released. `PRAGMA user_version` holds the applied version; each file runs in one transaction with the version update; a database with a newer version than the SDK knows is a storage failure. `scripts/check-repo-rules.sh` freezes every file in `Migrations/` that exists on `origin/main`.
- The queue uses WAL, as the Android Room queue does.
- The open is eager and has the open flag `SQLITE_OPEN_FILEPROTECTION_COMPLETEUNTILFIRSTUSERAUTHENTICATION`. A failed open, a failed migration, and a database that is not a SQLite file all throw `StorageError`, which `initialize()` (ticket 08) maps to `InitError.StorageFailure`. The SDK does not delete or repair a damaged file (ADR-0003 item 6 and the Android `InitCoordinator` keep the queue after a failure).
- The installation ID file is written to a temp file, synced, and renamed. The temp file has the class (`.completeFileProtectionUntilFirstUserAuthentication`) and `isExcludedFromBackup`; a rename keeps both. A file that is not a lowercase UUIDv4 is replaced with a WARN, as on Android.

iOS 15 executor (D9): `SerialExecutor.asUnownedSerialExecutor()` has a default implementation only on iOS 17 and later. For the iOS 15 target the executor must implement it: `UnownedSerialExecutor(ordinary: self)`. Without it the build fails with "requires 'asUnownedSerialExecutor()' to be available in iOS 15.0 and newer". Run time on an iOS 15 runtime is still not verified (no such runtime installed).

Facts checked on the Simulator:

| Question | Result |
| --- | --- |
| SQLite code for an open failure | `SQLITE_CANTOPEN` (14) for a missing directory, a read-only directory, and a database file with mode 000. `SQLITE_NOTADB` (26) for a file that is not a database. A damaged page opens fine and fails the first read with `SQLITE_CORRUPT` (11). |
| The open failure before the first unlock | Not reproducible. The Simulator does not enforce Data Protection. The code of the real failure is not known; the code treats every open failure the same, so it does not depend on the code. Needs a device. |
| Class of the database, `-wal`, `-shm`, `-journal`, and the installation ID file | Not verifiable. The Simulator reports `completeUntilFirstUserAuthentication` for every file (`URLResourceValues.fileProtection`, `fcntl(F_GETPROTECTIONCLASS)` returns 3), also for a file that was set to `complete` (the setting is ignored and reads back as the default). `attributesOfItem[.protectionKey]` returns nil. The class tests pass on the Simulator without proving that the SDK set the class; they check on a device. |
| Side files made again have the class | The test removes `-wal` and `-shm` after a close (this SQLite leaves them as empty files after a close) and checks that SQLite makes them again and that they report the class; the `-journal` test does the same in rollback-journal mode. Same limit as the row above. |
| `signaldock/` directory class inherited | Not verifiable. The SDK does not set a class on the directory. |
| `isExcludedFromBackup` on the installation ID file | Reads back `true` after the SDK sets it. |

Not covered by ticket 07: the opt-out marker file (ADR-0034), the public API, and the sender. The backup exclusion is set on the whole `signaldock/` directory (see the next Amendment).

## Amendment (2026-10-28, ticket 07 review, D6)

D6 named only the installation ID file for `isExcludedFromBackup`. Android keeps every SDK file in `noBackupFilesDir`. The owner decided on 2026-10-28:

- The SDK sets `isExcludedFromBackup = true` on the whole `signaldock/` directory, so the installation ID, the queue database, its side files, and later files (for example the opt-out marker) stay out of backups, as on Android (ADR-0001).
- The flag stays on the installation ID file too, because D6 names the file and the flag on the directory is one more thing that a restore could differ on. The directory flag is the rule; the file flag is a second guard.
- The known limit of D6 stays: Apple does not guarantee that an excluded item never appears on a restored device.
- The tests check that the directory and the file read back `true`. Not verified: that a real backup and restore leaves the directory out (the Simulator cannot do a restore).

Also from the review: the connection waits up to 2.5 s for a lock (`sqlite3_busy_timeout`, the Android framework default); a transaction that cannot roll back closes the connection; the WAL pragma must answer `wal`.

## Amendment (2026-10-28, ticket 06 review, D10)

D10 said the Swift models are "written to `generated/swift`". The coordinator decided on 2026-10-28:

- `scripts/codegen.sh` writes the Swift models to `sdk-ios/Sources/SignalDock/Generated/`, one `.swift` file per model and no metadata file. Reason: under D11 a host gets the SDK only from the source ZIP. A SwiftPM target cannot name a path outside the package, and a symlink to `generated/swift` would dangle in the ZIP. The Kotlin and TypeScript outputs stay in `generated/`.
- The drift check (step 1 of `scripts/verify-contract.sh`) regenerates the Swift models into a temporary folder (second argument of `codegen.sh`) and diffs it with that folder. A hand edit of a generated file fails the check.
- `swift format lint` skips `Sources/SignalDock/Generated`: it is generator output (`internal`, 4-space, no documentation), and the drift check guards it. The required-reason API scan of `scripts/check-repo-rules.sh` covers it, because it is under `sdk-ios/Sources`.

## Ticket 06 results (2026-10-28, Swift wire models)

The generator path of D10 works; the hand-written fallback is not used. Facts come from running the pinned openapi-generator 7.26.0 `swift6` on the contract and from tests on the iOS 27.0 Simulator (Xcode 27.1, Swift 6.4).

- Options: `--type-mappings DateTime=String` (`occurredAt` stays a String, as in the Kotlin step) and `validatable=false` (the default output has `ArrayRule`, `StringRule`, and `NumericRule` helpers that live in the generator's `Infrastructure` folder). The script keeps only the model files and removes `public` at the start of a line, because `swift6` has no non-public switch. The models are `internal` and use `Foundation` `Codable` only. The generator names the error model `ModelErrorResponse`.
- The property value is an enum with `typeBool`, `typeDouble`, and `typeString`. The generator makes two identical enums: `EventIngestPropertiesValue`, the send type of `EventIngest.properties` (the one `track` uses, ticket 08), and `PropertyValue`, which only `EventReceiptDetail` uses. Both try `Bool`, then `Double`, then `String`; `JSONDecoder` does not read `1` or `0` as `Bool`. A number is a `Double`, so it is kept by value only for integers within +-(2^53-1) (spec section 3; Android `MAX_SAFE_INTEGER`) and for doubles. The encoder writes `2` for `2.0`: the spelling can differ, the value does not (D10). Ticket 08 must reject a host `Int` or `Int64` outside that range.
- Tests: `sdk-ios/Tests/SignalDockTests/WireModelTests.swift` reads every file in `fixtures/roundtrip` and `fixtures/sdk-wire/android`, decodes it, encodes it again, and compares JSON values (numbers by value, `true` is not `1`). A response `null` is read as absent, because the models omit nil fields.
- Not verified: decoding was run only on the iOS 27.0 runtime, not on iOS 15 or 16 (no such runtime is installed). `Foundation` `JSONDecoder` behavior for `Bool` and `Double` may differ on older runtimes.

## Ticket 08 results (2026-10-28, public API)

Code: `sdk-ios/Sources/SignalDock/` (`SignalDockSDK.swift`, `Configuration.swift`, `Results.swift`, `EventBuilder.swift`, `Internal/Init`, `Internal/Collection`). It mirrors the Android 0.3.0/0.4.0 public API (`SignalDock.kt`, `Results.kt`, `EventBuilder.kt`, `InitCoordinator.kt`). Delivery is ticket 09: `track` stores into the queue, and `initialize` opens storage.

These points were not fixed by D1 or D9. They are implementer decisions that the owner can change:

- **Type name.** The instance type is `SignalDockSDK` (the name of the ticket 05 placeholder), with `static func initialize(_:)`. Android calls it `SignalDock`. A Swift type with the name of its module (`SignalDock`) breaks `SignalDock.Name` lookups for the host and the `.swiftinterface` check. Rejected: `SignalDock`.
- **Cancellation.** Q114 says to propagate cancellation to cancelled callers. In Swift that is `throws(CancellationError)` on `initialize`, `track`, `clearQueue`, and `diagnostics`; nothing else is thrown. `setOptOut` does not throw: cancellation does not undo it. `initialize` is shared work: the attempt is an unstructured task, waiters hold continuations, and a cancelled waiter leaves alone. After the commit, `track` throws `CancellationError` for a cancelled caller (as Android does when the result is delivered), writes the WARN, and the event stays stored.
- **`isOptedOut()` is synchronous** (owner decision 2026-10-28, D9 rule above; Android reads `@Volatile var optedOut`). `OptOutStore` stays an actor on its own serial executor and does the marker file work; the flag is in `OptOutFlag`, read under an `NSLock` (iOS 15 safe), see the amendment below. `track` reads the flag first (ADR-0034 decision 2), and `set` changes the flag before it writes the marker, with no `await` between.
- **`track(isolation:_:_:)`** has the usual `isolation: isolated (any Actor)? = #isolation` first parameter, so the non-`Sendable` builder closure runs on the caller's actor. The builder is a `struct` passed `inout`: `track("x") { $0.property("k", 1) }`.
- **Property overloads** `String`, `Bool`, `Int`, `Int64`, `Double`. `Int` and `Int64` outside +-(2^53-1) give `numberOutOfRange`; a whole `Double` above the limit too (Android parity). The wire type is `EventIngestPropertiesValue`.
- **Validation differences from Android that Swift forces.** A Swift `String` cannot hold a lone surrogate, so that `forbiddenCharacter` case cannot happen (invalid UTF-16 is repaired to U+FFFD when the host builds the string). Swift treats canonically equal strings as equal, so property keys `é` (one code point) and `e` + U+0301 are one key: the call returns `duplicateKey`, where Android stores two properties. Names are stored as given (no normalization). The whitespace check works on Unicode scalars with the fixed set of `fixtures/validation/whitespace-set.json`.
- **Config.** `SignalDockConfig` timeouts are `TimeInterval` seconds, with the Android rule (positive, finite, whole milliseconds at most `Int32.max`). The endpoint is parsed with `URLComponents`; scheme `http` or `https` (case-insensitive) and a non-empty host are needed, and `http` needs `allowInsecureEndpoint` (ADR-0035). The collection key is left out of `description`, `debugDescription`, and `dump`.
- **Failure messages.** `InitError.storageFailure(message:)` and `TrackResult.storageError(message:)` hold `StorageError(<kind>, <code>)` or the type name of another error: codes only (the Android message is the exception class name).
- **Logging.** `os.Logger`, subsystem `local.signaldock`. The SDK has a closed set of fixed messages (`LogMessage`), so no value is ever interpolated and nothing needs `.private`; a future value must use `privacy: .private` (`scripts/check-repo-rules.sh` rejects `.public`). `LogLevel.debug` writes debug, warning, and error; `.info` and `.warn` write warning and error; `.error` writes error; `.none` writes nothing. The Android storage-failure WARN text carries the exception name; here it is fixed text.
- **Wire bytes.** The stored payload is `JSONEncoder` with `sortedKeys` and `withoutEscapingSlashes`, so equal events give equal bytes. `sizeBytes` is the length of the encoded `BatchIngestRequest` with this encoder, as the sender (ticket 09) must reuse. `occurredAt` is written by an own civil-date function (no formatter), checked against `ISO8601DateFormatter` in a test.
- **Diagnostics values.** `DeliveryErrorKind` raw values are the Android names stored in `retry_state.lastErrorKind` (`NETWORK`, `RATE_LIMITED`, ...). `Diagnostics.contractDeprecation` is a normal stored property, because a Swift struct with an internal initializer has no binary-compatibility problem like the Kotlin constructor (ADR-0030). `DeprecationNotice` exists and is read by `diagnostics()`; ticket 09 records into it. The public value types have internal initializers.
- **`@unchecked Sendable`** is used in one SDK type, `OptOutFlag` (invariant in the amendment below), and in test helpers, with these invariants: `RecordingLogger.stored`, `SequenceIds.counter`, and `InitTests.Builder.count/failures` are read and written only under their own `NSLock`.
- **Public API check.** `scripts/verify-ios.sh api` builds the package with `BUILD_LIBRARY_FOR_DISTRIBUTION=YES` for arm64 Simulator and diffs the normalized `.swiftinterface` with `sdk-ios/api/SignalDock.swiftinterface`. The printing of an interface differs between compiler versions (Swift 6.4 prints `Module::Name`; older ones `Module.Name`), so **the compiler of CI is the single source of truth**: `sdk-ios/api/compiler-version` holds its major.minor. Locally, a different compiler prints a skip line (like step 8 without Xcode). In CI (`CI=true`) a different compiler or a different interface fails the `ios-test` step, which uploads the normalized actual interface and its compiler version as the artifact `api-actual`. `scripts/verify-ios.sh api-update <artifact directory>` copies both files into `sdk-ios/api/`; commit them. The normalization drops comment lines, `import` lines, `#if`/`#else`/`#endif` lines, a leading `nonisolated ` on synthesized `allCases` and `rawValue`, and rewrites `Module::Name` to `Module.Name`.

Run times on an iOS 15 runtime are still not verified (no such runtime). The open failure before the first unlock is still not reproducible on the Simulator; the tests use a database file without permission (`SQLITE_CANTOPEN`), the same kind of failure.

## Amendment (2026-10-28, ticket 08 review, D9 and `OptOutFlag`)

Owner decision: `isOptedOut()` is synchronous. D9 now says: Kotlin non-suspend functions map to synchronous Swift functions.

`OptOutFlag` (`Internal/Storage/OptOutStore.swift`) is `final class ... : @unchecked Sendable`. Invariant: its `NSLock` guards `stored`, and `stored` is read and written only inside that lock. Each access holds the lock for one load or one store and calls nothing else, so the lock is never nested and cannot deadlock. `NSLock` works on iOS 15 (`Mutex` would need iOS 18). `OptOutStore.isOptedOut` is `nonisolated` and reads the flag; `set` stays on the actor, changes the flag first, then writes the marker (ADR-0034 decisions 2 and 8). `EventCollector.track` reads the flag first, without `await`.

## Amendment (2026-10-28, ticket 08 review, enum evolution)

Owner decision: a minor version may add a case to a public result enum (`TrackResult`, `InvalidReason`, `InitError`, and the diagnostics enums). This is additive and follows Android, where `OptedOut` was added to `TrackResult` in 0.2.0. The enums are not marked `@frozen`. The `.swiftinterface` check shows an added case as a diff, so the change is made on purpose. The integration guide (ticket 18) tells hosts: minor versions can add cases; use `default` in `switch`.

## Amendment (2026-10-28, ticket 08 review, owner decisions on names, throws, and duplicate keys)

- **Type name `SignalDockSDK`, module `SignalDock`.** Same-name module and type breaks qualified lookup (`SignalDock.Name` resolves to the type, not the module) and the `.swiftinterface` check. Vendors that avoid it: Sentry (`SentrySDK`) and PostHog (`PostHogSDK`) suffix the type; Amplitude, Braze, Adjust, and Datadog suffix the module; only Mixpanel and AppsFlyer share the name. The iOS type differs from Android's `SignalDock` on purpose.
- **Typed throws stay.** `async throws(CancellationError)` on `initialize`, `track`, `clearQueue`, and `diagnostics` (Q114; ADR-0017: nothing else is thrown).
- **Duplicate keys.** `é` (U+00E9) and `e` + U+0301 are one key in Swift, and the wire model is a `[String: ...]` dictionary, so iOS returns `duplicateKey` where Android stores two properties. Accepted as a Swift-forced difference. `fixtures/validation/canonical-equivalent-keys.json` holds the keys and the expected result per platform; `TrackTest.kt` and `TrackTests.swift` both load it.

## Ticket 09 results (2026-10-28, delivery)

Code: `sdk-ios/Sources/SignalDock/Internal/Delivery/` (`Sender`, `Batcher`, `AckValidator`, `RetryPolicy`, `BatchTransport` with `URLSessionBatchTransport`, `DeliveryTrigger`, `BackgroundDrain`, `DeliveryCoordinator`) and `Internal/Init/DeprecationNotice.swift`. Corpus: `fixtures/sdk-wire/ios/`. It mirrors the Android `internal/delivery`, `internal/scheduling`, and `internal/transport` code and [ADR-0006](0006-sdk-delivery-decisions.md) decisions 2 to 10, 12; [ADR-0030](0030-sdk-contract-deprecation-warning.md) for the headers. Client reports (ADR-0036) are ticket 10, not here.

Built as the design sources say:

- The sender is an `actor`. The final-result table, Retry-After (never capped, saturating), Full Jitter backoff, hold (30 minutes), 413 split (cap halves, doubles after each valid ACK, cleared at 50), the clock-jump clamp (ADR-0025), and the ACK in one `QueueStore.applyAck` transaction are ported one to one; tests cover every row of the table and every status the table names.
- An actor is not a lock: its methods can interleave at each `await`, so a second request could start while one is in flight. The sender therefore keeps an explicit lock (`locked` and a queue of continuations) around one batch; it is released between batches. A test holds a request in flight and checks that a second run waits for the lock and sends nothing.
- The request body is the stored `payloadJson` bytes inside the wrapper. `transportEncoder()` sorts keys, so the wrapper that the generated `BatchIngestRequest` encodes is `{"events":[...],"installationId":"..."}` (events first). The sender writes that order, and a test checks that the body equals the re-encoded `BatchIngestRequest` byte for byte and has the size of `batchRequestBytes`. The Android order is `installationId` first; the two corpora are compared by JSON value (D10), so the order does not matter.
- Triggers: 20 stored events, or 10 s after the first event stored since the last drain started (`DeliveryTrigger`, with an injected timer); one drain at launch (the SDK is built) and one at `UIApplication.willEnterForegroundNotification`; the sender refuses to send before the persisted next-attempt time, so none of these checks the time itself. One drain at `didEnterBackgroundNotification` inside `beginBackgroundTask`: the task begins in the notification handler (before the work), the drain runs in a task, the expiration handler cancels that task and ends the background task, and the end happens once (a flag). A cancelled request throws `CancellationError`, which leaves the queue and the retry state as they were; nothing is counted as a failure. The same event IDs and bytes go out at the next drain, and the server answers `duplicate` if the first request had arrived.
- Deprecation headers are read from every response before the status is handled; `DeprecationNotice.observe` keeps the latest valid value of each field and writes one WARN per process.

Implementer decisions that the design sources do not fix (the owner can change them):

- **`URLSession` has two timeouts, the contract has four.** `timeoutIntervalForResource` is the call timeout. `timeoutIntervalForRequest` (no progress while connecting, writing, or reading) is the largest of the connect, write, and read timeouts. With the defaults (10 s each) it equals each of them. A host that sets one of the three lower than the others gets the larger value for that phase. Alternative: a watchdog per phase from `URLSessionTaskDelegate` progress callbacks (needs a clock and a timer in the transport). `waitsForConnectivity` is off, and there is no cache, no cookie storage, and no credential storage.
- **Redirects** are declined in the session delegate (`willPerformHTTPRedirection` answers `nil`), so a 3xx response is the final result, as on Android (Q123).
- **ACK body** is read only for status 200 and only up to 1 MiB (`session.bytes(for:)`; the rest of the body is never read).
- **Retry-After on an invalid 200.** ADR-0006 decision 5 says a valid Retry-After applies to every row; the Android `Sender.applyResponse` passes `null` for an invalid ACK. The iOS sender follows the ADR text. Android is the one that differs.
- **HTTP dates** are read by hand (no `DateFormatter`, so no locale or OS dependence). Retry-After accepts `GMT` and `UTC` and a one- or two-digit day. Android's `SimpleDateFormat` `zzz` accepts any zone name; that is not ported. Sunset is the strict IMF-fixdate with the literal `GMT` (ADR-0030). The weekday name must be a weekday but is not compared with the date, like Android's non-lenient `SimpleDateFormat`.
- **Log text.** The messages are a closed set (ticket 08), so the deprecation WARN has no date: "server contract version is deprecated; see diagnostics for the dates". The Android WARN holds the Sunset date.
- **A background task that the system refuses** does not stop the drain: it runs without a task and may get a little time.
- **No periodic send; an in-foreground retry timer (owner decision, 2026-10-28).** D8 first listed only the triggers above. The owner added one: when a drain ends with a wait while the app is active, `DeliveryTrigger` arms the injected `TriggerTimer` once for the persisted next-attempt time (the delay is that time minus now, at least 0), and a fire requests a drain. The next drain cancels it and arms a new one, and entry into the background cancels it; no timer runs in the background. This matches the Android WorkManager `requestAt` while the app is in the foreground. `.scratch/ios-sdk/spec.md` D8 records the trigger. Tests use the injected clock and a manual timer.
- **Review round 1 (PR 258).** The timer sleep is capped at one day and cannot trap on a huge wait (a Retry-After is never capped); the next drain arms a new timer. The active flag (`ActiveFlag`) changes without `await` at the background entry, before the background drain starts; the event timer and the retry timer are cancelled and not started while inactive, and the initial value is the real `applicationState` (a background launch arms no timer). Expiration cancels the request in flight whichever drain owns it (`Sender.cancelInFlight`). A waiter for the sender lock can be cancelled and leaves the line.
- **Review round 2 (PR 258).** Only `.background` counts as not active at build time: a normal foreground launch reports `.inactive` and posts no lifecycle notification, so `== .active` kept the timers off for the whole first foreground session. A background launch still arms no timer. CI failure of two `TransportTests` (`.failed(.timeout)` after 47 to 61 s): the transport and the stub did not change between the passing and the failing run. On the CI sanitizer runner (3 cores) the 268 parallel tests starve libdispatch and the cooperative pool for 30 s and more (a probe showed a GCD timer and a task sleep waking 33 s and 27 s late, while a plain thread woke on time), so a real 5 s, 10 s, or 30 s URLSession timeout ended a request that the stub had not yet seen. The transport tests now use 600 s timeouts (they check the session settings, not a real timeout), and the test with a 1 MiB ACK body, which took 57 s there, uses a small `ackLimit`. A race in `TriggerTests.noTimerRunsInTheBackgroundAndNoneIsArmedWithoutAWait` (the flag changed before the drain's follow-up ran) is closed with `DeliveryTrigger.waitUntilIdle`.
- **A multi-event 413 during a hold recheck** clears the deadline and continues with a smaller batch, as the Android `split` does (ADR-0006 decision 6: a split is not a temporary failure; decision 8: only a valid ACK releases the hold, and `hold` stays set until then).
- **The ACK is applied after the transport returns even if the caller was cancelled:** the server has committed, and dropping the ACK would make the next drain resend (Android parity).
- **A clear during a selection.** `clearQueue()` can run between the sender's head read and its group read. The group read then returns nothing; the sender looks again and `buildBatch` throws on empty rows (no `precondition`), so the host app never traps. A test runs `deleteAll` between the two reads through a seam.
- **Retry-After date parsing is stricter than Android** in more than the zone names: Android's `SimpleDateFormat` ignores case and accepts 1-digit hours and other widths; the iOS reader needs the exact English weekday and month names and two-digit hour, minute, and second. A value that Android accepts and iOS does not is ignored, and the normal backoff applies. The Retry-After on an invalid 200 difference is local issue 27 (Android).
- **The comparison cannot see the sign of zero.** `double_neg_zero` is `-0` on iOS and `0.0` on Android; they are equal by value, and the server accepts both.
- **Wire corpus.** Same case names and the same content as `android/`, built through the real `track()` path with the Android test's fixed clock, IDs, and installation ID, so the JSON values are equal. The size cases are padded to the limit as the Android event sees it: the iOS event is 4 bytes smaller for the same content (`"ios"` against `"android"`), so these iOS events are 4 bytes below 16 KiB (the exact limit is tested in `TrackTests`). Regeneration: `TEST_RUNNER_UPDATE_SDK_WIRE=1 scripts/verify-ios.sh tsan`, then review the diff. The comparison test runs in Swift (`WireCorpusTests`) and in the server (`SdkWireCorpusTest`).
- **Open: the smallest subnormal `Double`.** Swift writes `Double.leastNonzeroMagnitude` as `5e-324`; Java writes it as `4.9E-324`. The server accepts a non-integer decimal only if it equals `BigDecimal.valueOf(double)` (`IngestRules.validNumber`), so the server rejects the Swift text with `INVALID_PROPERTIES` although both SDKs send the same `Double` and the design says "finite Double values" (spec section 3). The iOS `all-property-types` case therefore does not have `double_tiny`, and both comparison tests list that property as a known difference. Options: relax `validNumber` (accept a literal whose `Double` is finite and equals the literal's shortest round-trip form), or reject subnormal values in the SDK. Owner decision (2026-10-28): the server rule is relaxed in a separate server PR; this PR keeps `double_tiny` as the recorded known difference, and local issue 28 re-adds it and drops the known-difference entries once the server accepts `5e-324`. It affects only values below about 1e-322.

Test seams in production code (internal): `DeliveryDependencies` (transport, jitter, timer, background tasks, thresholds, notification center, `afterDrain`), `Sender` hooks `betweenReads` and `onLockWait`, `BackgroundDrainer.lastTask`, and `URLSessionBatchTransport.init(config:protocolClasses:)`.

Not verified here: run time on an iOS 15 runtime; `beginBackgroundTask` and its expiration on a real device or with a real `UIApplication` (the tests use a fake `BackgroundTasks` and post the notifications on a private `NotificationCenter`); behavior of `URLSession` timeouts against a real slow server (the tests check the configuration, not the clock); a run against the Compose server.

## Amendment (2026-10-28, ticket 11, tracking plan Swift output)

- **File.** `tracking-plan/generate.mjs` also writes `tracking-plan/generated/swift/TrackingPlan.swift` (the Kotlin layout; ADR-0021 decision 4). The drift check is step 7 of `scripts/verify-contract.sh`, which already diffs the whole `generated/` folder.
- **Shape.** Like the Kotlin output (ADR-0021 decision 5): `internal enum EventNames` (lowerCamel constants), and one `internal` function per event in an `extension SignalDockSDK`, for example `productViewed(isolation:productId:priceMinor:)`. It has the shape of `track` (caller isolation first, `async throws(CancellationError)`) and calls it. Required properties are non-optional parameters; optional ones are `T? = nil` and are left out when `nil`. `integer` is `Int64` (the contract range is plus or minus (2^53 - 1)), `number` is `Double`. Required parameters come first. The file uses the public API only and is `swift format` clean. `scripts/verify-ios.sh lint` passes `--follow-symlinks`, so it lints the generated file through the test link (without the flag swift-format skips a link and exits 0).
- **Validation.** A property key whose camelCase name is a Swift keyword, or is `isolation` (the `track` parameter), is a plan error.
- **Compiled in the test target.** `sdk-ios/Tests/SignalDockTests/TrackingPlan.swift` is a symbolic link to the generated file, so `SignalDockTests` compiles it without a copy and without a change of `Package.swift`. SwiftPM does not allow a target path outside the package root, and a link is the form of the Kotlin precedent (the demo adds `tracking-plan/generated/kotlin` as a source directory). `TrackingPlanTests` calls each function on a real SDK and checks the stored event. `generate.test.mjs` reads the Swift text for the same name, keys, types, and optionality as Kotlin and TypeScript.
