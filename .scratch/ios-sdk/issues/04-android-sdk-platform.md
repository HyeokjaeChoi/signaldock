# 04. Android SDK 0.4.0 sends `sdkPlatform`

Status: resolved
Type: task
Blocked by: 01, 02
Execution: Done (PR #251, 2026-10-28 UTC)

## Scope and completion criteria

Spec D2, D3. ADR-0046.

1. Every stored event and client report carries `sdkPlatform: "android"`. Events already queued by 0.3.0 keep their stored bytes (ADR-0006 decision 3) and go without the field; the server defaults them (D3).
2. Move the Android corpus to `fixtures/sdk-wire/android/` and regenerate it with `sdkPlatform` (`UPDATE_SDK_WIRE=1`, `WireCorpusTest.kt`); the server `SdkWireCorpusTest` reads the new folder (ADR-0047 D10).
3. A wire fixture test fails when an event or client report has no `sdkPlatform`.
4. Version 0.4.0 (`SDK_VERSION` and `sdkVersion` stay equal). Public API dump and dependency baseline unchanged, or the change is explained.
5. CHANGELOG entry. Do not tag: the release is ticket 19.

Verification:
- SDK unit tests, `scripts/verify-contract.sh`, `bash scripts/check-repo-rules.sh`.
- `scripts/verify-consumer.sh` against a server from ticket 02.

## Verification run 2026-10-28

`JAVA_HOME=<repo>/.tools/jdk-21.0.12.1+1 ANDROID_HOME=<sdk> bash scripts/verify-consumer.sh` on origin/main (be20394): exit 0. Maven ZIP `signaldock-sdk-android-0.4.0-maven.zip`; AAR Kotlin metadata `mv=[2,2,0]`; consumer Kotlin Gradle plugin 2.2.10; `:app:assembleRelease` (R8 9.4.24) `BUILD SUCCESSFUL`. The script needs no emulator and sends no request to a server, so the Compose stack is not part of this check.
