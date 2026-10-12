# 16. Android demo app (part of issue 05)

Status: ready-for-agent
Type: task
Blocked by: None
Execution: Done (merged in PR #33, 2026-10-12)

## Scope and completion criteria

Compose demo app in `sdk/` as ADR-0005, section "Demo app: feature modules and unidirectional data flow", defines: `:demo:app`, `:demo:feature:shop`, `:demo:feature:diagnostics`, `:demo:core:ui`, `:demo:core:data`. compileSdk 37 (ADR-0006, decision 1). Spec section 6 "Demo and diagnostics": one `track` per user action, no recollection on recomposition, text that separates device collection results from server receipt results, diagnostics on screen entry and manual refresh.

Done when the app builds, its ViewModel tests pass, and it runs on an Android 17 (API 37.2) emulator against a local server, with commands, a screenshot, and results recorded here.

## Comments

2026-10-12: Split from issue 05.

2026-10-12: Implemented on branch `impl/16-demo-app`, rebased on main 60a8fc9.

Module map (inside the `sdk/` Gradle build; `sdk/settings.gradle.kts` includes them):
- `:demo:app` (`sdk/demo/app`): Application (`SignalDock.initialize` in `onCreate`), `MainActivity`, tab navigation (navigation-compose), manual ViewModel factories. Endpoint and collection key are BuildConfig fields read from `-Psignaldock.endpoint` / `-Psignaldock.collectionKey` or the gitignored `sdk/local.properties`. No key is committed. A debug-only `network_security_config.xml` allows cleartext to `10.0.2.2`. Release has no such rule (the merged release manifest has no `networkSecurityConfig`). The app declares `INTERNET`, because the SDK manifest declares no permissions.
- `:demo:feature:shop`: `ShopViewModel` (one `ShopUiState` StateFlow, `ShopIntent` events; list, detail, cart, checkout, done as `ShopStep`; `SavedStateHandle` for restore) and `ShopRoute` with `collectAsStateWithLifecycle()`.
- `:demo:feature:diagnostics`: `DiagnosticsViewModel` and `DiagnosticsRoute`. It reads on `ON_START` (screen entry) and on Refresh only.
- `:demo:core:ui`: theme, `SectionCard`, `LabeledValue`, `NoticeText`, and the note that "saved on device" is not server receipt.
- `:demo:core:data`: `SignalDockClient` interface, `SdkSignalDockClient`, typed `DemoEvent` (product_viewed, add_to_cart, checkout_started, purchase_completed).
- Feature modules do not depend on each other. No DI framework. Catalog additions in `gradle/libs.versions.toml`: Compose BOM library entries, activity 1.12.4, lifecycle 2.10.0, navigation 2.10.2, application and compose plugins, `demo-compileSdk`.
- compileSdk 37 (demo only; the SDK stays at 36 with `aarMetadata.minCompileSdk` 36), minSdk 24, targetSdk 36. Reason: Google Play requires 36 and has announced no API 37 deadline (ADR-0006 decision 1). The API 37.2 emulator still runs the app.

Design followed: ADR-0005 "Demo app: feature modules and unidirectional data flow"; ADR-0006 decision 1; spec section 6 "Demo and diagnostics", section 3, section 8.C (Q87); architecture.md "Components and responsibilities".

Commands and results (JAVA_HOME=$PWD/.tools/jdk-21.0.12.1+1, ANDROID_HOME=~/Library/Android/sdk):
- `scripts/setup-toolchain.sh`: installed JDK, oasdiff, openapi-generator-cli.
- `sdk/gradlew -p sdk :sdk-android:testDebugUnitTest :demo:feature:shop:testDebugUnitTest :demo:feature:diagnostics:testDebugUnitTest :demo:app:assembleDebug :demo:app:assembleRelease --no-daemon`: BUILD SUCCESSFUL. SDK: 90 tests, 0 failures. `ShopViewModelTest`: 7 tests, 0 failures. `DiagnosticsViewModelTest`: 5 tests, 0 failures. These are JVM tests with a fake `SignalDockClient`. They check: one track per action; double-tap and wrong-step intents do not track; restoring `SavedStateHandle` and re-collecting state do not track; a failed result is not re-tracked; diagnostics are read on entry and refresh only; a refresh during a read is ignored; a failed read keeps the last snapshot.
- `scripts/verify-contract.sh`: ALL CONTRACT CHECKS PASSED.

Emulator run (AVD `signaldock-api37`, pixel_8, `system-images;android-37.2;google_apis_ps16k;arm64-v8a`, `ro.build.version.sdk` 37, headless):
- PostgreSQL: `docker run -d --name signaldock-demo16-pg -e POSTGRES_PASSWORD=... -e POSTGRES_USER=signaldock -e POSTGRES_DB=signaldock -p 54329:5432 postgres:17.6-alpine`. Server: `cd server && ./gradlew :app:run` with `SIGNALDOCK_COLLECTION_KEY`, `SIGNALDOCK_JDBC_URL`, `SIGNALDOCK_DB_USER`, `SIGNALDOCK_DB_PASSWORD`, `PORT=8080` (test key, not committed). `/health` returned `ok`.
- `sdk/gradlew -p sdk :demo:app:installDebug`, then `adb shell input tap` through product, add to cart, cart, checkout, place order, and the Diagnostics tab.
- Server receipt: `select count(*) from events` returned 4 (product_viewed, add_to_cart, checkout_started, purchase_completed, with the typed properties). The Diagnostics screen showed pending 0, last ACK accepted/duplicate/rejected 1/0/0, no delivery error, no retry scheduled.
- Screenshots: `.scratch/signaldock-portfolio/evidence/16-demo-01-products.png`, `16-demo-02-detail.png`, `16-demo-03-cart.png`, `16-demo-04-checkout.png`, `16-demo-05-order-done.png`, `16-demo-06-diagnostics.png`.
- Emulator, server, and container were stopped at the end.

Limits: the UI was driven with `adb input`, not Compose UI tests. The ACK counts on the Diagnostics screen show the last ACK only.

2026-10-12: Record reconciled. Merged into main in PR #33 on 2026-10-12. Earlier branch and push notes above describe the state before that merge.
