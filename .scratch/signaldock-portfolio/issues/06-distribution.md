# 06. Maven ZIP and independent consumer app

Status: ready-for-agent
Type: task
Blocked by: 01, 04
Execution: In review

## Scope and completion criteria

Publish local.signaldock:sdk-android:0.1.0, build and run a separate Gradle consumer app with R8, and provide the Compose launch entry point and README.

Preserve the applicable contracts in the [agreed spec](../spec.md) and [final review](../final-review.md). Distinguish implementation, execution, and failure evidence. Assess completion after recording the outputs and actual verification.

## Comments

2026-10-09: Created after the full Q127 agreement.

2026-10-12: Implemented on branch `impl/06-distribution` (not pushed; no PR yet).

File map:
- `sdk/sdk-android/build.gradle.kts`: `maven-publish` for the release variant with sources jar; task `signaldockMavenZip`.
- `compatibility/consumer-android/`: independent consumer build (own settings, wrapper, `app/`). Gets the SDK only from `-Psignaldock.repo=<unzipped folder>`.
- `scripts/verify-consumer.sh`: ZIP, unzip to a clean temp folder, consumer `assembleRelease`.
- `docs/adr/0009-maven-zip-and-consumer-app.md`; ADR-0003 item 9 and `TOOLCHAIN.md` updated with the verified R8 version.
- `README.md` (status banner, ZIP, adding the SDK, consumer app), `architecture.md` (one sentence), `.github/workflows/repo-rules.yml` (new job `consumer-r8`, no emulator).
- Evidence: `evidence/06-consumer-initial.png`, `evidence/06-consumer-diagnostics-acked.png`.

Design followed: architecture "Independent distribution and verification"; spec section 6 "Reproduction and deliverables", Q126, section 7 Distribution row, section 9 item 5; ADR-0003 items 2 and 9; ADR-0005 "SDK: one publication module"; ADR-0006 decision 1; ADR-0008 (Compose entry point, `10.0.2.2`).

Commands and results:
- `sdk/gradlew -p sdk :sdk-android:testDebugUnitTest :sdk-android:assembleRelease --no-daemon`: BUILD SUCCESSFUL, 90 tests, 0 failures.
- `sdk/gradlew -p sdk :sdk-android:signaldockMavenZip --no-daemon`: BUILD SUCCESSFUL. ZIP holds `.aar`, `-sources.jar`, `.pom`, `.module`, `maven-metadata.xml` (plus checksums) under `local/signaldock/sdk-android/0.1.0/`. The POM lists kotlin-stdlib (compile) and kotlinx-serialization-json, kotlinx-coroutines-android, room-runtime, work-runtime, retrofit, okhttp (runtime).
- `scripts/verify-consumer.sh` (ZIP unzipped into a fresh `mktemp` folder): BUILD SUCCESSFUL, `:app:minifyReleaseWithR8` ran. The first attempt failed with "compiled with an incompatible version of Kotlin" (metadata 2.4.0, compiler 2.2.0); fixed by the Kotlin Gradle plugin 2.4.21 in the consumer's root `buildscript` (ADR-0009 decision 4).
- `scripts/verify-contract.sh`: ALL CONTRACT CHECKS PASSED.
- Emulator run: `docker compose up -d --build`; AVD `signaldock-api37` headless; `adb install -r app-release.apk`; tap "Track consumer_ping"; after about 15 s "Show diagnostics" showed `lastAck ... accepted=1, duplicate=0, rejected=0`, `queuedEvents=0`, `lastDeliveryError=null`. Server: `psql` on the db container returned one row `consumer_ping`, sdk_version 0.1.0; the Dashboard nginx `GET /v1/events?from=2026-10-12T00:00:00+09:00&to=2026-10-13T00:00:00+09:00` with `SignalDock-Contract-Version: 1` returned the same event (eventId `e7e0f11b-...`, receivedAt 2026-10-12T04:00:26.059Z). The emulator and Compose (`down --volumes`) were stopped afterwards.

R8 version: 9.3.28, from the `mapping.txt` header of the consumer release build (`# compiler: R8`, `# compiler_version: 9.3.28`, `# min_api: 24`). No Kotlin-metadata warning in the build output.

Consumer rules: the AAR ships no `proguard.txt`. None were needed: the minified APK initialized the SDK and delivered an event (Retrofit, Room, WorkManager, OkHttp, kotlinx.serialization bring their own rules). Recorded in ADR-0009 decision 6.

Limits: one event on one emulator (API 37), not a physical device. The release APK uses the debug signing key. Cleartext to `10.0.2.2` is allowed in the release build of the consumer app only (ADR-0009 decision 5). Consumers need Kotlin 2.4 or newer. CI job `consumer-r8` was added but has not run on GitHub. Other completion criteria (offline recovery, ACK loss, and so on) are not covered by this issue.
