---
status: accepted
---

# SDK cost for a host app (issue 36, spec Q151)

The user chose three numbers (Q151): APK size increase, initialization time, and memory (PSS). This ADR records the method and the result. The choices are implementer decisions that the user can change. They add no product behavior and change no SDK code.

## Decisions

1. **The baseline is the independent consumer app without the SDK.** `compatibility/consumer-android` takes the SDK from the unzipped Maven ZIP (ADR-0009) and builds with R8 (`isMinifyEnabled`, no keep rules of its own). The Gradle property `-Psignaldock.sdk=false` builds the baseline: no `sdk-android` dependency, no coroutines dependency, and the source directory `src/nosdk/kotlin` instead of `src/sdk/kotlin`. The default build is unchanged in behavior (it uses the SDK), and `scripts/verify-consumer.sh` still builds it. A new app was not needed. The two variants have the same manifest, resources, layout, and Gradle configuration. They differ in: the SDK and its libraries; the two classes in `src/sdk` or `src/nosdk` (the baseline has the same views and no SDK call); the application ID (the baseline ends in `.nosdk`, so both APKs are installed on the device at the same time and the runs can be interleaved).
2. **Test hooks in the SDK variant of the consumer app.** `ConsumerApplication` now calls `SignalDock.initialize()` on the main thread, in a coroutine that starts undispatched on `Dispatchers.Main.immediate`, and logs `init main_ns=... total_ns=... result=...` (tag `SignalDockHostCost`) with `SystemClock.elapsedRealtimeNanos()`. `main_ns` is the main-thread time until `initialize()` first suspends. It includes the coroutine setup and the class loading that the call causes, so it is an upper bound for the SDK alone. `total_ns` runs until the result is ready (the SDK works on its own IO scope). `MainActivity` calls `track()` N times when it starts with `--ei burst N` and logs `burst_done`. The measurement needs `result=success`, so the script passes a dummy collection key (an empty key fails validation and `initialize()` returns at once) and the endpoint `http://127.0.0.1:9`, where nothing answers: no event leaves the device, a server on the host never receives these events, and the SDK behaves like an app that is offline.
3. **APK size** is read from the R8 release APK of each variant: file bytes, `apkanalyzer apk download-size`, and the sum of `classes*.dex` (uncompressed and as stored in the APK). `dependencies.txt` lists the direct runtime dependencies from the POM in the ZIP and the libraries that are on the consumer's resolved `releaseRuntimeClasspath` with the SDK and not without it. Resources are not shrunk (the consumer has no `shrinkResources`).
4. **Startup:** `am force-stop`, then `am start -W`, and the `TotalTime` line, 20 runs per variant. Each variant first has two launches that are not counted (the first start after an install runs dex verification and creates the empty database). The variants alternate in each run (sdk first in odd runs, baseline first in even runs), so a slow period of the host hits both. p50 is the median (the mean of the two middle values when n is even); p95 uses the nearest-rank rule, so with n=20 it is the 19th value. The summary also gives the paired difference of each run pair.
5. **PSS:** `dumpsys meminfo <package>`, the `TOTAL PSS` line, after the launch plus 5 seconds idle (the same 20 runs as the startup runs), and after a burst of 500 `track()` calls plus 5 seconds idle (10 runs per variant, separate launches). The baseline burst run only logs. The summary gives median, min, max, and the paired difference.
6. **One script:** `scripts/host-cost-measure.sh <serial>` builds the ZIP and both variants, installs them, runs everything above, and writes `perf/sdk-results/host-cost-api<api>/`: `apk-size.csv`, `startup.csv` (TotalTime and idle PSS per run), `init.csv`, `pss-burst.csv`, `dependencies.txt`, `meta.json` (emulator, API, build fingerprint, host, git revision, APK and mapping hashes, R8 version, parameters), and `summary.md`. Emulators only, macOS, no server needed. Parameters: `HOSTCOST_RUNS`, `HOSTCOST_BURST_RUNS`, `HOSTCOST_BURST`, `HOSTCOST_IDLE`.

## Result (API 37 emulator, R8 9.3.28, SDK 0.1.0)

| Metric | Without SDK | With SDK | Delta |
| --- | --- | --- | --- |
| APK bytes | 21,606 | 638,435 | +616,829 |
| Download size (apkanalyzer) | 17,264 | 565,335 | +548,071 |
| dex bytes (one `classes.dex`) | 3,012 | 969,120 | +966,108 |
| `am start -W` TotalTime p50 / p95 / max, ms (n=20) | 235 / 252 / 254 | 238 / 302 / 310 | paired median +1.5 ms |
| Main thread until `initialize()` suspends p50 / p95 / max, ms (n=20) | not applicable | 0.26 / 2.97 / 4.97 | |
| `initialize()` result ready (wall) p50 / p95 / max, ms | not applicable | 92 / 166 / 178 | |
| PSS 5 s after start, median KiB (n=20) | 18,002 | 22,020 | +4,014 (paired 3,576 to 4,507) |
| PSS 5 s after 500 `track()` calls, median KiB (n=10) | 18,010 | 34,607 | +16,532 (paired 15,671 to 17,296) |

The SDK brings 49 libraries on the runtime classpath (7 direct dependencies in the POM: kotlin-stdlib, kotlinx-serialization-json, kotlinx-coroutines-android, room-runtime, work-runtime, retrofit, okhttp). The baseline app is tiny (two classes, no AndroidX), so the percentage of the APK growth is not meaningful; the byte delta is. Cold start time is the same at the median (+1.5 ms paired), because the SDK does its work off the main thread. The p95 and max with the SDK are 50 to 60 ms higher, because two of the 20 runs took 302 and 310 ms (the others took 247 ms or less). The cause is not established (host load or work in the SDK; the main-thread time inside `initialize()` was at most 4.97 ms); with n=20 this is a tendency, not a proven difference. The extra PSS after the burst is mostly the SQLite and Room write path and the queued events.

## Limits

- **Emulator numbers, not device numbers.** An arm64 API 37 emulator (`signaldock-api37`, image `google_apis_ps16k`) on one Mac that also ran other work. A physical device has other CPU, storage, and memory behavior. The agent cannot reach one.
- The run used the emulator that was already running (`emulator-5554`); another app (not part of this repository) was installed on it. The measurement apps were in the foreground in each run.
- Release signing is the debug key; R8 behavior is the same, the artifact is not a store build.
- One host, one run of the whole script. The spread of the 20 and 10 runs is in the CSV files. PSS is a sample of a moving value, so only differences of the order of the spread or more should be read.
- Startup is `am start -W` to the first activity; it does not measure first frame drawn or the time until the SDK has sent its first event.
- Resource shrinking, a host with its own Room or OkHttp versions (shared libraries would cost less), and a Compose app (a larger baseline) are not measured.

## Amendment (2026-10-24 UTC)

`host-cost-measure.sh` can run on a physical phone when the owner sets `SIGNALDOCK_PHYSICAL_DEVICE=1`. It writes `host-cost-phone-api<api>`, reads logcat from a start time instead of clearing it, and changes no device setting. The two consumer apps (R8 release, debug-signed) stay installed. Details: [ADR-0042](0042-measurement-on-a-physical-device.md).
