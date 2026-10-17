---
status: accepted
---

# SDK Baseline Profile (issue 70, spec Q168)

The user approved this item on 2026-10-18 UTC. This ADR records the implementer decisions. The user can change them. The change adds no public API and no runtime dependency.

## Context

A host app compiles the SDK code only when it runs, or after the system has seen it run many times. A Baseline Profile lists the classes and methods of the hot paths. Android Runtime (ART) then compiles them ahead of time at install. Android documents this for apps and for libraries ([overview](https://developer.android.com/topic/performance/baselineprofiles/overview), [library guide](https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile-library)). The library guide names three modules: a sample app that uses the library, the library, and a test module that generates the profile. The library module applies `androidx.baselineprofile` and has a `baselineProfile(project(":generator"))` dependency. The profile is saved in the library under `src/main/generated/baselineProfiles`. Public example: `coil-kt/coil` commits `coil-core/src/androidMain/generated/baselineProfiles/baseline-prof.txt`, uses the plugin with a `filter { include("coil3.**") }`, and a `baselineProfile(projects.internal.benchmark)` dependency (read 2026-10-18 UTC). A code search found no profile in `DataDog/dd-sdk-android`, `getsentry/sentry-java`, `square/okhttp`, and `PostHog/posthog-android`. Gap: the search can miss files, so this is not proof that they have none.

## Decisions

1. **Plugin, not a hand-written file.** `androidx.baselineprofile` 1.5.0 and `androidx.benchmark:benchmark-macro-junit4` 1.5.0 are the latest stable versions on Google Maven (2026-10-18 UTC). They run with AGP 9.3.3 and Gradle 9.7.0: `generateBaselineProfile` ran and the committed file came from it. `uiautomator` is 2.4.0.
2. **Three modules, all in the `sdk` build.**
   - `:sdk-android` applies the plugin. `baselineProfile(project(":baseline-profile"))` is a generator link, not a runtime dependency. The filter includes `local.signaldock.**` and excludes `local.signaldock.profileapp.**`: Room, WorkManager, OkHttp, and Retrofit ship their own profiles, and the sample app is in the same package tree (the first generation held 27 sample-app rules; CodeRabbit found it).
   - `:profile-app` is a small sample app (`sdk/profile-app`). It calls `initialize()` with a dummy key and the endpoint `http://127.0.0.1:9` (nothing answers, so the sender runs its request and failure path), tracks 20 events, and reads `diagnostics()`. It is built with R8 and no keep rules of its own, like the consumer app. It is never published.
   - `:baseline-profile` is a `com.android.test` module with `BaselineProfileGenerator` (`BaselineProfileRule`) and `StartupBenchmark` (Macrobenchmark). It is never published.
3. **Why not `compatibility/consumer-android` or the demo app as the target.** The consumer app is a separate Gradle build. It gets the SDK from the Maven ZIP, so the plugin cannot link it to the library. The demo app needs Compose, a collection key, and UI automation, which makes the profile depend on screens that the SDK does not own. `:profile-app` has one screen and one call sequence.
4. **No `profileinstaller` dependency in the SDK.** A library does not need it: the host's AGP merges the `baseline-prof.txt` of the AAR into the app profile (verified: the consumer app of `scripts/verify-consumer.sh` has the SDK rules in `merged_art_profile` and a `assets/dexopt/baseline.prof` in its APK). The dependency baseline (ADR-0033) already lists `androidx.profileinstaller:profileinstaller:1.3.0`, which comes through other androidx libraries. A host app that is not installed from Google Play needs `profileinstaller` to apply the profile, and it has the library already. `:profile-app` declares `profileinstaller` 1.4.1 itself, because Macrobenchmark installs the profile through it. `dependencyGuard` shows no change. This keeps ADR-0033 and ADR-0009 (no new runtime dependency).
5. **The profile is committed, and is not generated in CI.** The file is `sdk/sdk-android/src/main/generated/baselineProfiles/baseline-prof.txt` (519 rules). Generation needs an emulator or device and about 3 minutes. Runs gave 541, 545, 518, and 519 lines, so the file is not byte-for-byte reproducible. CI only builds the AAR with the committed file. `automaticGenerationDuringBuild` stays off (the default).
6. **When to regenerate.** After a change of the hot paths (`initialize`, `track`, the Room queue, the sender, WorkManager scheduling), or at each release. Command: `sdk/gradlew -p sdk :sdk-android:generateBaselineProfile` with one booted emulator or device (the project used the AVD `faults37`, API 37). Gap: no check fails when the profile is stale.
7. **Measurement method.** Macrobenchmark `TraceSectionMetric("profileapp:sdk_ready")`: an async section from `MainActivity.onCreate` to the end of `initialize()`, 20 `track()` calls, and `diagnostics()`. `StartupTimingMetric` was tried first and failed on this emulator ("No RT frame slice associated with UI thread frame slice"), so no frame metric is used. The SDK starts from the activity, not from `Application.onCreate`: Macrobenchmark starts the process by a broadcast to install the profile, and an SDK that runs then schedules WorkManager jobs that start the process again after the compile step, which Macrobenchmark rejects ("must not be running prior to cold start"). Each iteration runs `pm clear` first, so the queue and the jobs start empty. The existing `scripts/host-cost-measure.sh` was not used: it uses `am start -W` on a build without a profile compile step and cannot switch the profile on or off.
8. **Size.** The profile is 56,109 bytes of text in the AAR. In a consumer APK it is `baseline.prof` plus `baseline.profm`, about 860 bytes after R8. `scripts/check-size-budget.sh`: delta 623,417 bytes (budget 700,000, ADR-0023). The budget is not changed.

## Result (API 37 emulator, 20 cold starts per row)

Metric: the trace section above. Raw files: `perf/sdk-results/baseline-profile-api37/` (`run2.json`, `run3.json`, `run4.json`, `control-no-sdk-rules.json`; the run order was run 2, control, run 3, run 4). Device: AVD `faults37`, `google/sdk_gphone16k_arm64/emu64a16k:17/CP41.260831.007/16416850:userdebug/dev-keys`, run with `androidx.benchmark.suppressErrors=EMULATOR`. The committed profile (519 rules) was used.

| Run | Mode | p50 ms | mean ms | p90 ms | min ms | max ms |
| --- | --- | --- | --- | --- | --- | --- |
| 2 | None | 495 | 538 | 766 | 261 | 1127 |
| 2 | Profile | 358 | 389 | 488 | 257 | 571 |
| Control: SDK rules removed | Profile | 387 | 562 | 1113 | 225 | 1367 |
| 3 | None | 749 | 847 | 1158 | 314 | 2320 |
| 3 | Profile | 351 | 390 | 630 | 197 | 846 |
| 4 | None | 413 | 436 | 601 | 288 | 667 |
| 4 | Profile | 410 | 404 | 552 | 218 | 599 |

- The merged profile lowers the p50 by 28 % (run 2), 53 % (run 3), and 1 % (run 4) against no compilation. The mean and p90 are lower in all three runs, but run 4 shows no p50 gain. The gain is not proved on this emulator.
- The SDK rules alone are not resolved by the emulator: the p50 is 358 ms and 351 ms with them and 387 ms without them (8 % to 10 % more), and the control spread is wider than that (p90 1113 ms). The control keeps the profiles of Room, WorkManager, and the other androidx libraries.
- The noise is large. The same mode gave a p50 of 495 ms and 749 ms in two runs, and an earlier run before the filter fix (not kept) gave 291 ms to 451 ms. Do not quote one number as the gain.

9. **Generator end signal.** `BaselineProfileGenerator` does not use a fixed wait. After the 20 `track()` calls, `profile-app` polls `diagnostics()` until `lastDeliveryError` is set (the sender made one request and failed). This runs outside the measured trace section. The app then shows "Delivery attempted". The generator waits for that text with UiAutomator (30 s) and fails if it does not come (CodeRabbit finding on PR #182). `StartupBenchmark` accepts the "Diagnostics" or the "Delivery" text.

## Limits

- Gap: emulator only. No physical device, no field data (Android Vitals), one app shape.
- Gap: fixed mode order. Every run measured all 20 samples of `startupNoCompilation` before the 20 samples of `startupBaselineProfile`. Emulator drift during a run can bias the comparison. A later run must alternate the order (or interleave the modes) and record the executed order before any gain is claimed.
- The profile helps only a host that builds with AGP 7.1 or newer and installs by a path that applies the profile (Google Play, or `profileinstaller` on first run). A host that does not do this gets no change and no harm.
- The profile ships rules for names that R8 renames in the host. The host's AGP rewrites them with the R8 mapping (checked in the consumer app: 546 SDK rules before R8, the rules after R8 use the short names).
- A stale profile does not break anything: a rule for a missing method is ignored. It only loses benefit.
- Pre-existing, not part of this change: `sdk/gradlew -p sdk build` fails in `:demo:core:data:testDebugUnitTest` (`SdkSignalDockClientTest` builds a config with an `http://` endpoint and no `allowInsecureEndpoint`, so `initialize()` most likely fails since ADR-0035; not checked against `main`). CI does not run that test class. It is reported in the PR.
