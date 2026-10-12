---
status: accepted
---

# Maven ZIP and independent consumer app decisions (issue 06)

The design sources leave these points open (architecture "Independent distribution and verification"; spec section 6 "Reproduction and deliverables", Q126, section 9 item 5). This ADR records the choices made in issue 06. It adds no new product behavior.

## Decisions

1. **Publication.** `sdk/sdk-android` uses `maven-publish` for the `release` variant with a sources jar. Coordinate: `local.signaldock:sdk-android:0.1.0` (Q126). The POM and the Gradle module metadata list the runtime dependencies (kotlin-stdlib, kotlinx-serialization-json, kotlinx-coroutines-android, room-runtime, work-runtime, retrofit, okhttp). The module metadata also keeps `aarMetadata.minCompileSdk=36` (ADR-0006 decision 1). The generated transport models stay `internal` and are not part of the public API (ADR-0003 decision 2).
2. **One command.** `sdk/gradlew -p sdk :sdk-android:signaldockMavenZip` cleans `sdk-android/build/maven-repo`, publishes to it, and writes `sdk-android/build/distributions/signaldock-sdk-android-0.1.0-maven.zip`. `scripts/verify-consumer.sh` runs it, unzips the ZIP into a clean folder, and builds the consumer from that folder.
3. **Consumer app.** `compatibility/consumer-android/` (the directory the spec proposes). Own `settings.gradle.kts` and wrapper (Gradle 9.7.0, same checksum). Not included in `sdk/`. Only the group `local.signaldock` can come from the folder repository (`-Psignaldock.repo=<folder>`); everything else comes from Google and Maven Central, so the first build is not offline. It shares the tool pins in `gradle/libs.versions.toml` and nothing else. compileSdk 36, minSdk 24, targetSdk 36, `isMinifyEnabled = true`, no keep rules of its own. It uses plain Views, not Compose, because the app only has to prove the AAR. The release build uses the debug signing key so it installs on an emulator.
4. **Consumers need a Kotlin 2.4 compiler.** The SDK is compiled with Kotlin 2.4 (metadata 2.4.0). With AGP 9.3.3 the built-in Kotlin of a plain app defaults to 2.2.0 and fails with "compiled with an incompatible version of Kotlin ... can read versions up to 2.3.0". The consumer raises it with `classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")` in its root `buildscript`. This is a real requirement for every customer app and is documented in the README. Customers on AGP 8.x or an older Kotlin cannot use 0.1.0 (the same kind of cost as ADR-0006 decision 1).
5. **Cleartext in the release build.** The consumer release APK must reach the Compose stack at `http://10.0.2.2:8080`. A `network_security_config` in the main source set allows cleartext for the host `10.0.2.2` only; `usesCleartextTraffic="false"` stays. This differs from the demo app, which puts the same rule in the debug source set. The reason: the release build is the artifact under test, and the consumer app is a local verification app, not a distributed one. Other hosts stay HTTPS only.
6. **No consumer R8 rules in the SDK AAR.** The 0.1.0 AAR contains no `proguard.txt`. The consumer R8 build and run (item 7) pass without one: Retrofit, Room, WorkManager, OkHttp, and kotlinx.serialization ship their own rules in their artifacts. The SDK adds no reflection of its own (ADR-0003 item 9). Add `consumerProguardFiles` only when a run shows a missing rule.
7. **R8 version.** AGP 9.3.3 bundles R8 **9.3.28**. Evidence: the header of `mapping.txt` from the consumer release build says `# compiler: R8`, `# compiler_version: 9.3.28`, `# min_api: 24`. This meets the Kotlin 2.4 requirement (9.1.29 or newer). The build printed no Kotlin-metadata warning. ADR-0003 item 9 and TOOLCHAIN.md are updated.

## Not verified here

- Release signing and Play distribution. The consumer uses the debug key.
- Physical devices. The run used the headless AVD `signaldock-api37` (Android 17 / API 37 image) against the Compose stack.
- A consumer on a Kotlin version other than 2.4.21, or on AGP 8.x.
