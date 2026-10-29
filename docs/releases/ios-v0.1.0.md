# SignalDock iOS SDK 0.1.0

First release of the iOS SDK (tag `ios-v0.1.0`). License: Apache-2.0 ([LICENSE](../../LICENSE)). The iOS version numbers are separate from the Android SDK versions. This release has the behavior of Android SDK 0.3.0 ([ADR-0047](../adr/0047-ios-sdk.md) D1) in a native Swift package. **The server must support `sdkPlatform` before you use it:** see [Server and Dashboard first](#server-and-dashboard-first).

## What it is

An event SDK that stores events on the device and confirms server receipt through an ACK after commit. It has `SignalDockSDK.initialize`, `track` with a builder, `setOptOut`, `isOptedOut`, `clearQueue`, and `diagnostics`. It has the same validation, queue limits, ACK handling, backoff, hold, 413 split, contract deprecation warning, client reports, and HTTPS-only rule as Android. It has no external dependency (system SQLite). The package ships a privacy manifest (`DeviceID` and `ProductInteraction`; no tracking; no required-reason API). Host-defined event properties are your own data: declare any other data type in your app's privacy manifest ([ADR-0047](../adr/0047-ios-sdk.md) D14).

## What is included

- `signaldock-sdk-ios-0.1.0.zip`: a Swift package source folder `SignalDock/` with `Package.swift`, `Sources/`, and `LICENSE`.
- `signaldock-sdk-ios-0.1.0.zip.sha256`: the SHA-256 file.
- A build provenance attestation for the ZIP ([ADR-0039](../adr/0039-release-provenance-attestation.md), [`release-ios.yml`](../../.github/workflows/release-ios.yml)).

The ZIP is not code-signed. Maven Central, remote Swift Package Manager, CocoaPods, and XCFramework are not used.

## Install

1. Verify the download from the folder that holds both files:

   ```sh
   shasum -a 256 -c signaldock-sdk-ios-0.1.0.zip.sha256
   ```

2. Verify the attestation (GitHub CLI 2.102.0 or newer; older versions have attestation check flaws):

   ```sh
   gh attestation verify signaldock-sdk-ios-0.1.0.zip --repo HyeokjaeChoi/signaldock \
     --signer-workflow HyeokjaeChoi/signaldock/.github/workflows/release-ios.yml
   ```

   The command must exit 0 (it prints `Verification succeeded!` in a terminal). The attestation shows that the iOS release workflow of this repository built the ZIP. It does not show that the code is free of defects.
3. Unzip. In Xcode choose File, Add Package Dependencies, Add Local, and select the `SignalDock/` folder. Add the product `SignalDock` to the app target. The folder name holds no version, so a package reference keeps working across versions. `compatibility/consumer-ios/` does the same.

Steps in full: [Integration guide, section 13, iOS SDK](../integration-guide.md#13-ios-sdk) ([한국어](../integration-guide.ko.md)).

## Requirements

- iOS 15 or later. The package manifest uses `swift-tools-version: 6.0`, so use Xcode with Swift 6 or later. The consumer app builds in Swift 6 language mode.
- Tested with Xcode 26.6 (Swift 6.3, the CI toolchain) and Xcode 27.1 (Swift 6.4, local runs). Gap: older Xcode and Swift versions were not tested.
- Apps only. The public entry points are unavailable in app extensions ([ADR-0047](../adr/0047-ios-sdk.md) D9).
- Use an `https://` endpoint. An `http://` endpoint needs `allowInsecureEndpoint: true`, and App Transport Security must allow the host.

## Server and Dashboard first

The iOS SDK sends `sdkPlatform: "ios"` in every event and client report. A server without `sdkPlatform` support rejects the field with `UNKNOWN_FIELD`, and the SDK holds its queue. Deploy the server and the Dashboard with `sdkPlatform` support before you ship an app with this SDK, including the cloud demo server if the app points at it ([ADR-0046](../adr/0046-sdk-platform-field.md) decision 8). The same support is in the Android release [v0.4.0](v0.4.0.md), including the migration cost of `V4`. To block an iOS version on the server, set `SIGNALDOCK_BLOCKED_SDK_VERSIONS` with an item such as `ios:0.1.0`.

## Size and host cost

Method and limits: [ADR-0047](../adr/0047-ios-sdk.md), amendments "ticket 16, measurement method" and "ticket 17, size budget". Result of `perf/sdk-results/ios-0.1.0-20261029/` (iPhone 18 Pro Simulator, iOS 27.0, Xcode 27.1, Apple M2 Pro, SDK 0.1.0, one run on a Mac that ran other work). Release archive of `compatibility/consumer-ios`, arm64, unsigned:

| Metric | Without SDK | With SDK | Delta |
| --- | --- | --- | --- |
| Main binary, bytes | 164,560 | 553,024 | +388,464 |
| `.ipa` form (unencrypted), bytes | 49,263 | 202,566 | +153,303 |
| Cold start to first view with `initialize()` inside, median ms (n=20) | 413.7 | 413.9 | paired median +1.1 |
| Memory 5 s after launch, median MiB (n=20) | 21.34 | 21.69 | paired median +0.38 |
| `initialize()` on the main thread, settled, median ms | floor 0.015 | 0.051 | |
| `track()` on the main thread, median ms (n=5000) | not applicable | 0.035 | |

- The SDK is a static link. It adds no embedded or third-party dynamic library. The main binary links three more system libraries: CoreFoundation, UIKit, and `libsqlite3`.
- CI gate `ios-size-budget` fails when the SDK adds more than 175,000 bytes (`.ipa` form) or 440,000 bytes (main binary). The first CI run on Xcode 26.6 measured +158,012 and +390,112 bytes.
- **Simulator caveat.** These are Simulator numbers, not device numbers: the Simulator uses host CPU and host memory accounting. The Mac was shared with other work, so use the medians only. The `.ipa` form is not encrypted, so a store download is larger. The app is a tiny consumer app, not what a large app pays. Not measured: a physical iPhone, a signed store `.ipa`, App Thinning, and the time until the first event is sent.

## Known limits and platform differences

- No periodic background send. The SDK sends in the foreground, once when the app enters the background (inside `beginBackgroundTask`), at launch, and at return to the foreground, with one in-process retry timer while the app is active. Events left after a background kill go out at the next launch. It does not use `BGTaskScheduler`.
- The installation ID file and the queue are excluded from backup. A restored device can rarely share an installation ID.
- The Data Protection class is `completeUntilFirstUserAuthentication`. Before the first unlock after a restart, `initialize` can return `storageFailure`; call it again later.
- The public result enums (`TrackResult`, `InvalidReason`, `InitError`, and the diagnostics enums) are not `@frozen`. A minor version can add a case, so a `switch` over them needs a `default`.
- Not verified: any run on an iOS 15 runtime (the GitHub macOS runner has none; the tests run on the oldest runtime it has), a physical iPhone, backup restore, a full disk, and the data protection class on a device. The Simulator cannot reproduce a backup restore or a full disk.
- The attestation. The `gh attestation verify` command has not run on an iOS ZIP before this release. Run it after the workflow succeeds and record the result in [issue 19 of the iOS SDK work](../../.scratch/ios-sdk/issues/19-release-android-0.4.0-ios-0.1.0.md).
- Out of scope for 0.1.0 (deferred, not rejected): remote Swift Package Manager, CocoaPods, XCFramework, app extensions, Objective-C and completion-handler APIs, `BGTaskScheduler`, measurement on a physical iPhone, and the connection of the iOS app to the cloud demo.
