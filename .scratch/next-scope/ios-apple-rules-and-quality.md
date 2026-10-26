# iOS SDK: Apple rules and quality (research notes)

Scope: primary sources only. "not confirmed" = could not verify in a primary source.

## A. Apple requirements for third-party SDKs

- Privacy manifest scope: an SDK needs `PrivacyInfo.xcprivacy` if it uses a required-reason API, collects data, enables data collection, or contacts tracking domains. Packages, XCFrameworks, and Xcode projects can all carry one. https://developer.apple.com/documentation/bundleresources/privacy-manifest-files
- Packaging: add the file to the target's bundle resources. For static libraries, use a framework target with Mach-O type "Static Library" and add the file to bundle resources. Not confirmed: exact SwiftPM `resources:` syntax (verify before coding). https://developer.apple.com/documentation/bundleresources/privacy-manifest-files
- Reporting rule: an SDK must report the APIs its own code uses. The app manifest or other SDKs' manifests do not cover it. https://developer.apple.com/documentation/bundleresources/describing-use-of-required-reason-api
- Enforcement: since May 1, 2024, App Store Connect rejects uploads that use required-reason APIs without a declared reason. https://developer.apple.com/documentation/bundleresources/describing-use-of-required-reason-api
- Reason codes that fit our design (verified on the reasons page):
  - `NSPrivacyAccessedAPICategoryFileTimestamp`: `stat`, `fstat`, `lstat`, `getattrlist`, `creationDate`, `modificationDate`. Code `C617.1` = metadata of files inside the app/app-group/CloudKit container. `3B52.1` = user-granted files. `0A2A.1` = third-party wrapper only (not for our SDK's own use). https://developer.apple.com/documentation/bundleresources/app-privacy-configuration/nsprivacyaccessedapitypes/nsprivacyaccessedapitypereasons
  - `NSPrivacyAccessedAPICategoryDiskSpace`: `volumeAvailableCapacityForImportantUsageKey`, `statvfs`, `statfs`. Code `E174.1` requires the app to "behave differently based on disk space in a way that is observable to users". A silent queue cap likely does not fit. https://developer.apple.com/documentation/bundleresources/app-privacy-configuration/nsprivacyaccessedapitypes/nsprivacyaccessedapitypereasons
  - `NSPrivacyAccessedAPICategorySystemBootTime`: `systemUptime`, `mach_absolute_time()`. Code `35F9.1` = measure elapsed time within the app or enable timers. Avoid these for queue timing, or declare `35F9.1`. Not confirmed: whether `ContinuousClock`/`SuspendingClock` are covered internally. https://developer.apple.com/documentation/bundleresources/app-privacy-configuration/nsprivacyaccessedapitypes/nsprivacyaccessedapitype
  - `NSPrivacyAccessedAPICategoryUserDefaults`: `CA92.1` = data only the app itself reads/writes. The design uses a file, so this is not needed unless UserDefaults is added. https://developer.apple.com/documentation/bundleresources/app-privacy-configuration/nsprivacyaccessedapitypes/nsprivacyaccessedapitypereasons
  - Not in any required-reason category per the category page: `isExcludedFromBackup` (write), file I/O, `URLSession`, SQLite calls. Not confirmed: whether SQLite's internal `stat` calls count. https://developer.apple.com/documentation/bundleresources/app-privacy-configuration/nsprivacyaccessedapitypes/nsprivacyaccessedapitype
- Collected data types (`NSPrivacyCollectedDataType*`): `DeviceID` ("device-level ID") and `ProductInteraction` ("app launches, taps...") and `OtherDiagnosticData` are the closest fits. Which one an install UUID maps to is a judgment call, not confirmed by Apple. https://developer.apple.com/documentation/bundleresources/app-privacy-configuration/nsprivacycollecteddatatypes/nsprivacycollecteddatatype
- Tracking: `NSPrivacyTracking` = use of data "as defined under the App Tracking Transparency framework". If true, `NSPrivacyTrackingDomains` is required. An SDK with no tracking sets it to false and omits domains. https://developer.apple.com/documentation/bundleresources/app-privacy-configuration/nsprivacytracking and https://developer.apple.com/documentation/bundleresources/app-privacy-configuration/nsprivacytrackingdomains
- Signatures: Xcode checks that the same developer signed a new SDK version, "where the listed SDKs are used as binary dependencies". The page does not say source-only Swift packages are exempt. Not confirmed for source ZIP. https://developer.apple.com/support/third-party-SDK-requirements/
- Commonly used SDK list: our SDK (zero dependencies) is not on the list. The list rule also covers repackaged variants of listed SDKs. Manifest is still needed under the scope rule above. https://developer.apple.com/support/third-party-SDK-requirements/

## B. Swift 6 concurrency for a library

- Sendable: a public type is Sendable only if it meets the rules (value types with sendable members, or a `final` class with immutable sendable stored properties). Conformance must be declared in the same file unless unchecked. https://developer.apple.com/documentation/swift/sendable
- `@unchecked Sendable`: you take responsibility for correctness (for example, a lock or a queue). It also disables the same-file rule. Use only with a written invariant. https://developer.apple.com/documentation/swift/sendable
- Actor reentrancy: actor state "can change across an `await` when an interleaved task mutates that state". Re-check invariants after each `await`. https://raw.githubusercontent.com/swiftlang/swift-evolution/main/proposals/0306-actors.md
- Long work on an actor blocks other tasks on that actor: "A function that does a large amount of computation on an actor's executor will prevent other tasks from making progress on that actor." https://raw.githubusercontent.com/swiftlang/swift-evolution/main/proposals/0338-clarify-execution-non-actor-async.md
- Blocking SQLite calls: Apple and swift.org do not state a specific recommendation (not confirmed). SE-0392 says "executors should enqueue and process the job on another thread instead" and allows a custom `SerialExecutor` on an actor via `unownedExecutor`. That supports a dedicated serial queue or thread behind the sender actor, not the cooperative pool. https://github.com/swiftlang/swift-evolution/blob/main/proposals/0392-custom-actor-executors.md
- Community view (secondary, not official): blocking calls on the cooperative pool can starve other work. https://forums.swift.org/t/actors-threading-and-sqlite/49710
- Cancellation: `withTaskCancellationHandler` runs the handler "always and immediately" on cancel, even if the operation never checks. Handlers that take locks risk deadlock. https://developer.apple.com/documentation/swift/withtaskcancellationhandler(operation:oncancel:isolation:)
- `@MainActor`: a singleton actor on the main dispatch queue. Use it for UIApplication-facing observers, not for the sender. https://developer.apple.com/documentation/swift/mainactor
- Clock injection: `Clock` "is expected to schedule with a default tolerance", and the protocol is the hook for injecting time. Proposal status (SE-0329) not confirmed as final. https://developer.apple.com/documentation/swift/clock and https://forums.swift.org/t/se-0329-third-review-clock-instant-and-duration/54727
- Deterministic tests: `confirmation(expectedCount:)` checks the event count when the closure returns; use `expectedCount: 0` for "must not happen". Use an injected Clock instead of sleeps. https://developer.apple.com/documentation/testing/testing-asynchronous-code

## C. Library API quality

- Swift API Design Guidelines: write one-sentence summary docs; "Avoid abbreviations."; "Omit all labels when arguments can't be usefully distinguished". https://www.swift.org/documentation/api-design-guidelines/
- Guidelines have no `@available` or deprecation guidance. Deprecation syntax such as `@available(*, deprecated, renamed:)` is not confirmed in a primary source. https://www.swift.org/documentation/api-design-guidelines/
- Library evolution: off by default; use it only when a framework "is going to be built and updated separately from its clients". Swift packages built together "should not be built with library evolution support". So `BUILD_LIBRARY_FOR_DISTRIBUTION` is not needed for a source-distributed package. https://swift.org/blog/library-evolution/
- `package` access (SE-0386, Swift 5.9): cross-module within one package, not visible to clients. Use it for internal shared types; keep public surface minimal. https://github.com/swiftlang/swift-evolution/blob/main/proposals/0386-package-access-modifier.md
- `@frozen` and `@usableFromInline`: not confirmed in a primary source here. Not needed without library evolution.
- os.Logger: interpolated values are redacted by default. `privacy: .private` hides values; `auto(mask:)` / `private(mask:)` log a hash so the same value can be correlated. Do not log raw installation UUID or event payloads. https://developer.apple.com/documentation/os/logger and https://developer.apple.com/documentation/os/oslogprivacy
- DocC for public API: not confirmed in this pass. Check Apple DocC docs before adding a rule.

## D. Background execution

- `beginBackgroundTask(withName:expirationHandler:)`: each call "must be balanced by a matching call" to `endBackgroundTask`. If time expires without that call, the system kills the app. Expiration handler is called "before time expires". Remaining time is in `backgroundTimeRemaining`; no fixed budget is guaranteed. https://developer.apple.com/documentation/uikit/uiapplication/beginbackgroundtask(withname:expirationhandler:)
- Call it early, before entering background: a call at the end of `applicationDidEnterBackground` may not get the assertion. https://developer.apple.com/documentation/uikit/uiapplication/beginbackgroundtask(withname:expirationhandler:)
- App extensions: `UIApplication.shared` is unavailable in extensions (secondary: Apple forum, Xcode 13 beta 3 release note). The beginBackgroundTask page says extensions should use `ProcessInfo.performExpiringActivity(withReason:using:)`. https://developer.apple.com/documentation/uikit/uiapplication/beginbackgroundtask(withname:expirationhandler:) and https://developer.apple.com/documentation/foundation/processinfo/performexpiringactivity(withreason:using:) and https://developer.apple.com/forums/thread/685369
- SDK guard: compile out `UIApplication.shared` in extension targets with `@available(iOSApplicationExtension, unavailable)` or use `ProcessInfo.performExpiringActivity`. The `expired` block must stop work quickly, and it can run twice. https://developer.apple.com/documentation/foundation/processinfo/performexpiringactivity(withreason:using:)

## Gates or rules to propose

- Ship `PrivacyInfo.xcprivacy` as a package resource. Declare `NSPrivacyAccessedAPICategoryFileTimestamp` with `C617.1` only if the SDK reads file metadata. Source: privacy-manifest-files, reasons pages.
- Ban `systemUptime` and `mach_absolute_time` in SDK code, or declare `35F9.1` with a review note. Source: category page.
- No disk-space APIs for the queue cap unless the behavior is user-observable (`E174.1`). Source: reasons page.
- Set `NSPrivacyTracking` to false and omit tracking domains; declare the install ID under `DeviceID` (judgment, confirm with Apple). Source: collected-data and tracking pages.
- Public types are `Sendable` by construction. Any `@unchecked Sendable` needs an ADR naming the lock or queue invariant. Source: Sendable doc.
- Sender actor uses a custom `SerialExecutor` backed by a dedicated queue or thread, so SQLite calls stay off the cooperative pool. Source: SE-0392, SE-0338.
- Actor methods re-check state after every `await`. Source: SE-0306.
- Each `beginBackgroundTask` has one `endBackgroundTask` in the expiration handler; extension builds never reference `UIApplication.shared`. Source: UIApplication and beginBackgroundTask docs, ProcessInfo doc.
- Log installation ID and event payloads with `privacy: .private`; no `.public` on identifiers. Source: OSLogPrivacy doc.
- No `BUILD_LIBRARY_FOR_DISTRIBUTION` for the source package; keep internal types `package`-scoped. Source: library-evolution blog, SE-0386.
