---
status: accepted
---

# SDK release (R8) measurement (issue 33, item 2)

ADR-0014 measured a debug build and named the missing R8 release run as not done. `scripts/device-measure.sh` built only the debug `androidTest` APK, so it could not measure R8. This ADR records how the release path works and what it shows. The choices are implementer decisions that the user can change. They add no product behavior and change no product code.

## Decisions

1. **A library test build cannot show R8 on the SDK, so a measure app does.** With `testBuildType = "release"` and `isMinifyEnabled = true`, AGP shrinks only the test code: the tested library classes stay unchanged in the mapping file. The measurement therefore runs in `sdk/measure-app`, an application module (`applicationId local.signaldock.measureapp`, no UI, never published) that takes `:sdk-android` as a normal dependency, like a consumer app. Its `release` build type has `isMinifyEnabled = true` and `isShrinkResources = true`, the default `proguard-android-optimize.txt`, and no keep rule for the SDK (ADR-0009 decision 6: the AAR ships no consumer rules).
2. **Same phases, one source.** The four phases moved to `sdk/sdk-android/src/measure/kotlin/.../MeasureRunner.kt`, which calls only public SDK API. The debug `androidTest` class `MeasurePhases` is now a thin JUnit wrapper around it, and the measure app calls it from an `Instrumentation` (`MeasureInstrumentation`). Both modules add that directory as a source directory. The only code change in the phases: the size of the first event is read from the `sizeBytes` column of `queue.db` instead of through the SDK's internal DAO, because an app module cannot see `internal` members. The value is the same field. A debug smoke run after the change passed.
3. **Signing: a debug-signed release variant.** The repository has no release keystore. The release build type uses the debug signing config. The app is not debuggable.
4. **Script: `MEASURE_BUILD=release scripts/device-measure.sh <serial> [scenario ...]`.** It builds `:measure-app:assembleRelease`, installs it, runs `am instrument ... local.signaldock.measureapp/.MeasureInstrumentation`, and writes `perf/sdk-results/api<api>-release-<scenario>/` (the debug layout of ADR-0014 decision 13 with `release-` before the scenario). Debug runs keep their names. A non-debuggable app has no `run-as`, so the script restarts adbd with `adb root` and reads `/data/data/<package>/files/measure/<runId>`. It stops with an error if `adb root` is not available (Google Play images). Emulators only, as before. Everything else (server, proxy, reconciliation, verdict) is unchanged.
5. **Evidence of R8 in the run.** `meta.json` of a release run has `build_type: "release"` and an `r8` object: `minifyEnabled`, `shrinkResources`, the SHA-256 of the APK and of `mapping.txt`, the number of mapping lines, the number of `local.signaldock` classes in the mapping, how many were renamed and how many removed, and one example (`local.signaldock.SignalDock -> r00`). `run.json` repeats `build_type` and `debuggable: false` from the device. The mapping file (about 77,000 lines) is not committed; its hash lets anyone compare a rebuild.
6. **Summaries only grow.** `perf/sdk_summarize.py` adds a `build_type` field (a run without it is `debug`), a Build column and the build name in each heading of `perf/sdk-results/summary.md`, and `build_type` in `perf/results/sdk-index.json` (regenerated, as ADR-0015 decision 7 says). The report page shows the build type next to the AVD and in the chart names. No `run.json` or raw file of a debug run changed.
7. **One AVD, one run per scenario.** The API 37 emulator (`signaldock-api37`, image `google_apis_ps16k`, `sdk_gphone16k_arm64`, Android 17) ran normal, offline and burst once each. API 24 was not run in release. The repository's `git_revision` in `meta.json` says `dirty` because the run came before the commit that adds `sdk/measure-app`.

## Result (API 37, debug against release, one run each)

| Scenario | Build | Verdict | Actual events/s | Max in flight | track() p50 / p95 / max ms | Drain s | PSS delta KiB |
| --- | --- | --- | --- | --- | --- | --- | --- |
| normal | debug | pass | 1.0 | 1 | 1.58 / 2.23 / 20.52 | 2.02 | 2008 |
| normal | release | pass | 1.0 | 1 | 0.77 / 1.23 / 3.51 | 1.49 | 1389 |
| burst | debug | pass | 99.99 | 4 | 0.85 / 2.29 / 52.14 | 0.06 | 2702 |
| burst | release | pass | 99.98 | 1 | 0.48 / 1.07 / 5.37 | 0.06 | 3436 |
| offline | debug | pass | 888 (back to back) | 1 | 0.85 / 2.70 / 24.42 | 16.57 | 3498 |
| offline | release | pass | 2103 (back to back) | 1 | 0.33 / 1.16 / 7.37 | 17.20 | 2667 |

All six runs reconcile with no missing, not-tracked or duplicate server rows. `track()` is about half as slow or faster in the release build, which matches ADR-0014's expectation that a debug build is the conservative side. Drain time is dominated by delivery (proxy, server, the SDK's own triggers), so it does not change. The offline drain includes the persisted retry wait, which was 3.5 s in the debug run and 0 s in the release run; the time after the wait was 13.02 s (debug) and 17.20 s (release), which is not a speed difference that one run can show. The PSS deltas show noise of some MiB (ADR-0014 decision 9): one run per cell does not show whether release uses more or less memory.

## Limits

- One run per scenario on one emulator on a shared host (load average is in `meta.json`). The numbers show an order of magnitude, not a distribution, and are not an SLA.
- No physical device (issue 33, item 3): the agent cannot reach one. The README says so.
- The release app is debug-signed and not Play-signed. R8 behavior is the same, but the artifact is not a store build.
- R8 ran with the library's default rules and no SDK keep rule. If a later SDK change needs a keep rule that this path would not reveal (for example reflection used only by a feature the phases do not call), the consumer app of ADR-0010 is the check for it.
