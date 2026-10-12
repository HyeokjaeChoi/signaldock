# 06. Maven ZIP and independent consumer app

Status: ready-for-agent
Type: task
Blocked by: 01, 04
Execution: Done (merged in PR #39, 2026-10-12)

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

Limits: one event on one emulator (API 37), not a physical device. The release APK uses the debug signing key. Cleartext to `10.0.2.2` is allowed in the release build of the consumer app only (ADR-0009 decision 5). Consumers need Kotlin 2.2 or newer (Q132; first version of this issue said 2.4). CI job `consumer-r8` was added but has not run on GitHub. Other completion criteria (offline recovery, ACK loss, and so on) are not covered by this issue.

CodeRabbit (`coderabbit review --base main --deep --agent`, 2026-10-12): 1 finding, minor. The comment in `sdk/sdk-android/build.gradle.kts` about consumer R8 rules contradicted ADR-0009 decision 6. Valid; the comment was reworded. No other findings.

## Comment 2026-10-12 (Q132: Kotlin 2.2 metadata)

What changed: `sdk/sdk-android/build.gradle.kts` keeps the Kotlin 2.4.21 compiler but sets `languageVersion` and `apiVersion` to `KOTLIN_2_2` and `coreLibrariesVersion = "2.2.21"`. No SDK source change was needed. `compatibility/consumer-android/build.gradle.kts` no longer has the `kotlin-gradle-plugin` classpath. `scripts/verify-consumer.sh` now fails if the consumer overrides the Kotlin Gradle plugin or if any class in the AAR has `kotlin.Metadata` `mv` above 2.2. ADR-0009 decision 4, README, TOOLCHAIN.md, and spec Q132 are updated.

Why stdlib is pinned: the first attempt with only languageVersion/apiVersion still failed in the consumer, because the POM pulled `kotlin-stdlib` 2.4.21 (metadata 2.4.0, unreadable by a 2.2 compiler). The pin fixes it; the POM now lists 2.2.21.

Commands and results:
- `sdk/gradlew -p sdk :sdk-android:testDebugUnitTest :sdk-android:assembleRelease --no-daemon`: BUILD SUCCESSFUL.
- `scripts/verify-consumer.sh`: BUILD SUCCESSFUL. Check printed `metadata versions: mv=[2,2,0]` (every class under `local/` in the AAR `classes.jar`, read with `javap -v`). Consumer build used Kotlin 2.2.10 (`buildEnvironment` shows `kotlin-gradle-plugin:2.2.10`; the Kotlin daemon ran `kotlin-compiler-embeddable-2.2.10`). No "incompatible version" error.
- R8: `mapping.txt` header `# compiler_version: 9.3.28`; no Kotlin-metadata warning.
- `scripts/verify-contract.sh`: ALL CONTRACT CHECKS PASSED.
- `sdk/gradlew -p sdk :demo:app:assembleDebug :demo:feature:shop:testDebugUnitTest :demo:feature:diagnostics:testDebugUnitTest --no-daemon`: BUILD SUCCESSFUL.
- Emulator (AVD `signaldock-api37`, headless, Compose stack): new release APK installed, "Track consumer_ping" tapped; diagnostics first showed `queuedEvents=1`, then `queuedEvents=0`, `accepted=1`, `lastAck` 1791780146246 (04:42:26 UTC). `psql` returned one row: `consumer_ping`, sdk_version 0.1.0, properties `{"tap": 1}`, received_at 2026-10-12 04:42:26.8+00. `evidence/06-consumer-diagnostics-acked.png` was replaced with the new screenshot. Emulator killed, `docker compose down` (no volumes removed), `.env` removed.

Limits: consumers on AGP 8.x or a Kotlin older than 2.2 are not verified. The SDK still uses the 2.4.21 compiler (R8 9.1.29 or newer is needed only for that compiler).

CodeRabbit (`coderabbit review --base main --deep --agent`, 2026-10-12, Q132 change): 3 findings, all valid and fixed. Major: the plugin-override check was a text grep only; `verify-consumer.sh` now also reads the effective Kotlin Gradle plugin from `buildEnvironment` and fails unless it is 2.2.x. Minor: the metadata scan now covers every class in the AAR, not only `local/`. Minor: the stale "Kotlin 2.4 or newer" limit in this issue now says 2.2 (Q132).

2026-10-12: Record reconciled. Merged into main in PR #39 on 2026-10-12. Earlier branch and push notes above describe the state before that merge.
