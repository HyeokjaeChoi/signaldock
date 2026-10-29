# 19. Release Android 0.4.0 and iOS 0.1.0

Status: ready-for-human
Type: task
Blocked by: 02, 03, 04, 10, 15, 17, 18

## Scope and completion criteria

Spec section 4.

1. The server and Dashboard with `sdkPlatform` support are deployed, including the cloud demo (`demo-v*` tag, ADR-0043), before any SDK tag.
2. The owner approves and pushes `v0.4.0` and `ios-v0.1.0`.
3. Both GitHub Releases have the ZIP, SHA-256, and a verified attestation (`gh attestation verify`).
4. Refresh the Android docs for 0.4.0, as the 0.3.0 release PR `97abd80` did: `docs/integration-guide.md` (version lines 9, 36, 54, 57, 362; the event-field table near line 298 gains `sdkPlatform`), `README.md` and `README.ko.md` (lines 87, 224, 227 and the equivalents), and the default version in `compatibility/consumer-android/app/build.gradle.kts`.
5. Record what ran in this file.

Verification:
- Release links, `gh attestation verify` output, one consumer install from each downloaded ZIP.

## Prepared (2026-10-29)

Files only; no tag was pushed and no release workflow ran. `sdkVersion`, `SDK_VERSION` (both 0.4.0) and `SignalDockSDK.version` (0.1.0) were already correct and are unchanged.

Added: `docs/releases/v0.4.0.md`, `docs/releases/ios-v0.1.0.md`.
Changed for item 4 (as `97abd80`): `docs/integration-guide.md` and `.ko.md` (version lines; `sdkPlatform` named in the list of fields the SDK adds), `README.md` and `README.ko.md` (status and version lines), `compatibility/consumer-android/app/build.gradle.kts` (default version), `architecture.md`, `docs/architecture/onepager-content.md` and `signaldock-onepager.html`, `SECURITY.md`, the bug report form, and `CHANGELOG.md` (`[Unreleased]` split into `[0.4.0]` and `[iOS 0.1.0]`, dated 2026-10-29; change the date if the tags come later).

Remaining owner steps, in order:

1. Deploy the server and Dashboard with `sdkPlatform` support, including a `demo-v*` tag for the cloud demo (the VM must be on).
2. Push `v0.4.0`.
3. Push `ios-v0.1.0`.
4. Run `gh attestation verify` on each downloaded ZIP (commands in the release notes) and do one consumer install from each ZIP from the downloaded ZIP: Android: `unzip signaldock-sdk-android-0.4.0-maven.zip -d <new empty folder>`, then `compatibility/consumer-android/gradlew -p compatibility/consumer-android -Psignaldock.repo=<that folder> -Psignaldock.version=0.4.0 :app:assembleRelease --no-daemon` (`scripts/verify-consumer.sh` rebuilds from source and takes no ZIP); iOS: `scripts/verify-consumer-ios.sh <downloaded signaldock-sdk-ios-0.1.0.zip>`.
5. Record the results in this file, then set `Status: resolved`. Update the lines that say no iOS release exists yet (README, integration guide section 13) and the README status and "Add the SDK to an app" sentences that say v0.4.0 is not tagged yet (`README.md`, `README.ko.md`). In `SECURITY.md`, remove "(not released yet)" from the released rows and set Android 0.3.x to "None (superseded by 0.4.x)". ADR-0047 gets a dated amendment, not an edit of its decision text.

Checks that ran on the release branch (2026-10-29, JDK 21, `ANDROID_HOME` set): `scripts/check-repo-rules.sh` exit 0; `scripts/release.sh 0.4.0 --check` ok; `scripts/release.sh 0.4.0` exit 0 (builds the ZIP and the R8 consumer, writes `dist/`; no tag, no publish); `sdk/gradlew -p sdk :sdk-android:testDebugUnitTest :sdk-android:apiCheck :sdk-android:dependencyGuard` exit 0; `scripts/build-ios-release-zip.sh 0.1.0 --check` ok; `scripts/verify-consumer.sh` exit 0.
