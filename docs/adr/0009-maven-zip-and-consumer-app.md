---
status: accepted
---

# Maven ZIP and independent consumer app decisions (issue 06)

The design sources leave these points open (architecture "Independent distribution and verification"; spec section 6 "Reproduction and deliverables", Q126, section 9 item 5). This ADR records the choices made in issue 06. It adds no new product behavior.

## Decisions

1. **Publication.** `sdk/sdk-android` uses `maven-publish` for the `release` variant with a sources jar. Coordinate: `local.signaldock:sdk-android:0.1.0` (Q126). The POM and the Gradle module metadata list the runtime dependencies (kotlin-stdlib, kotlinx-serialization-json, kotlinx-coroutines-android, room-runtime, work-runtime, retrofit, okhttp). The module metadata also keeps `aarMetadata.minCompileSdk=36` (ADR-0006 decision 1). The generated transport models stay `internal` and are not part of the public API (ADR-0003 decision 2).
2. **One command.** `sdk/gradlew -p sdk :sdk-android:signaldockMavenZip` cleans `sdk-android/build/maven-repo`, publishes to it, and writes `sdk-android/build/distributions/signaldock-sdk-android-0.1.0-maven.zip`. `scripts/verify-consumer.sh` runs it, unzips the ZIP into a clean folder, and builds the consumer from that folder.
3. **Consumer app.** `compatibility/consumer-android/` (the directory the spec proposes). Own `settings.gradle.kts` and wrapper (Gradle 9.7.0, same checksum). Not included in `sdk/`. Only the group `local.signaldock` can come from the folder repository (`-Psignaldock.repo=<folder>`); everything else comes from Google and Maven Central, so the first build is not offline. It shares the tool pins in `gradle/libs.versions.toml` and nothing else. compileSdk 36, minSdk 24, targetSdk 36, `isMinifyEnabled = true`, no keep rules of its own. It uses plain Views, not Compose, because the app only has to prove the AAR. The release build uses the debug signing key so it installs on an emulator.
4. **Consumers need Kotlin 2.2 or newer (Q132).** The SDK is compiled with the Kotlin 2.4.21 compiler, but `kotlin { compilerOptions { languageVersion/apiVersion = KOTLIN_2_2 } }` makes the class metadata 2.2.0, and `coreLibrariesVersion = "2.2.21"` makes the POM depend on `kotlin-stdlib` 2.2.21 (stdlib 2.4 has metadata 2.4 and a 2.2 compiler cannot read it). A consumer on AGP 9.x with its default built-in Kotlin (2.2.10 with AGP 9.3.3) builds with no Kotlin plugin override. `scripts/verify-consumer.sh` fails if the consumer overrides the Kotlin Gradle plugin or if any AAR class has metadata above 2.2. Revisit when the AGP default moves. Customers on AGP 8.x cannot use 0.1.0 (ADR-0006 decision 1).
   History: the first consumer build failed with "compiled with an incompatible version of Kotlin" (metadata 2.4.0, compiler 2.2.0). The first fix required Kotlin 2.4+ in every consumer (`classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")`). Q132 replaced it on 2026-10-12.
5. **Cleartext in the release build.** The consumer release APK must reach the Compose stack at `http://10.0.2.2:8080`. A `network_security_config` in the main source set allows cleartext for the hosts `10.0.2.2`, `127.0.0.1`, and `localhost` only (Q134: the last two serve a physical device through `adb reverse`); `usesCleartextTraffic="false"` stays. This differs from the demo app, which puts the same rule in the debug source set. The reason: the release build is the artifact under test, and the consumer app is a local verification app, not a distributed one. Other hosts, including a LAN address, stay HTTPS only: Android cannot allow an address range.
6. **No consumer R8 rules in the SDK AAR.** The 0.1.0 AAR contains no `proguard.txt`. The consumer R8 build and run (item 7) pass without one: Retrofit, Room, WorkManager, OkHttp, and kotlinx.serialization ship their own rules in their artifacts. The SDK adds no reflection of its own (ADR-0003 item 9). Add `consumerProguardFiles` only when a run shows a missing rule.
7. **R8 version.** AGP 9.3.3 bundles R8 **9.3.28**. Evidence: the header of `mapping.txt` from the consumer release build says `# compiler: R8`, `# compiler_version: 9.3.28`, `# min_api: 24`. This meets the Kotlin 2.4 requirement (9.1.29 or newer). The build printed no Kotlin-metadata warning. ADR-0003 item 9 and TOOLCHAIN.md are updated.

## Not verified here

- Release signing and Play distribution. The consumer uses the debug key.
- Physical devices. The run used the headless AVD `signaldock-api37` (Android 17 / API 37 image) against the Compose stack.
- A consumer on a Kotlin version other than AGP 9.3.3's default (2.2.10), or on AGP 8.x.

## Check of decision 4: runtime `kotlin-stdlib` (issue 57, 2026-10-17 UTC)

Question: the baseline `sdk/sdk-android/dependencies/releaseRuntimeClasspath.txt` (ADR-0033) shows `kotlin-stdlib` 2.3.20, but the POM says 2.2.21. Result: decision 4 holds. No code change.

- The POM and the Gradle module metadata of `local.signaldock:sdk-android:0.1.0` both declare `kotlin-stdlib` 2.2.21 (`compile` scope in the POM; `requires` 2.2.21 in both variants of the `.module` file).
- `kotlinx-serialization-core-jvm` 1.11.0 (a runtime dependency of the SDK) requires `kotlin-stdlib` 2.3.20 in its module metadata. Gradle selects the highest version, so the runtime classpath gets 2.3.20. `dependencyInsight` shows the selection reason "By constraint".
- The consumer app with Kotlin Gradle plugin 2.2.10 resolves the same way: `releaseCompileClasspath` has `kotlin-stdlib` 2.2.21 (2.2.10 -> 2.2.21); `releaseRuntimeClasspath` has 2.3.20. The SDK dependencies that need 2.3.20 have runtime scope, so the Kotlin 2.2 compiler never reads them. `scripts/verify-consumer.sh` passed: AAR metadata `mv=[2,2,0]`, consumer Kotlin Gradle plugin 2.2.10, R8 release build successful.
- A runtime stdlib newer than the compile stdlib is safe: the SDK classes use `apiVersion` 2.2 and the stdlib is backward compatible.

Gap: no emulator run in this check; the R8 release build is the evidence. Gap: a host app that adds `kotlinx-serialization` 1.11.0 to its own compile classpath also gets stdlib 2.3.20 at compile time; this is the host app's own choice and not tested here.
