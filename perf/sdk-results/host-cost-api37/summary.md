# SDK cost for a host app (issue 36)

Measured on an emulator, not on a physical device: signaldock-api37 (API 37, Android 17, sdk_gphone16k_arm64, arm64-v8a) on Apple M2 Pro, one shared host (load average in `meta.json`). Numbers show the order of magnitude, not an SLA. Method and limits: ADR-0020.

Both APKs are the independent consumer app (`compatibility/consumer-android`), R8 release, built from the Maven ZIP of `local.signaldock:sdk-android:0.1.0`. The baseline is built with `-Psignaldock.sdk=false`: no SDK dependency, no SDK call, and no coroutines library. Both ran interleaved on the same emulator. Run 2026-10-14T10:58:09Z, git 01bd686.

## APK size (R8 release, R8 9.3.28)

| | Without SDK | With SDK | Delta |
| --- | --- | --- | --- |
| APK bytes | 21606 | 638435 | +616829 (2854.9 %) |
| Download size (apkanalyzer) | 17264 | 565335 | +548071 (3174.6 %) |
| dex bytes (1 / 1 files, sum of classes*.dex) | 3012 | 969120 | +966108 |
| dex bytes in the APK (compressed) | 1591 | 465122 | +463531 |

The baseline is a tiny app (two classes), so the percentage is large and says little; the byte delta is what an app adds. Resources are not shrunk (the consumer has no `shrinkResources`). Libraries that come with the SDK are in `dependencies.txt`: the POM lists 7 direct dependencies, and 49 libraries are new on the release runtime classpath (Room, WorkManager, OkHttp, Retrofit, kotlinx.serialization, coroutines and their dependencies).

## Startup (20 cold starts per variant, interleaved; 2 warm-up launches not counted)

| Metric (ms) | Without SDK p50 / p95 / max | With SDK p50 / p95 / max | Unit |
| --- | --- | --- | --- |
| `am start -W` TotalTime | 235 / 252 / 254 | 238 / 302 / 310 | ms |

Paired difference of TotalTime (with minus without, run by run): median 1.5 ms, min -46, max 91.

Main-thread time in `SignalDock.initialize()` (consumer app, `SystemClock.elapsedRealtimeNanos()`, n=20): `initialize()` is called from `Application.onCreate` in a coroutine that starts undispatched on the main thread; the time until it first suspends is the main-thread cost (it includes the coroutine setup). The SDK does its work on its own IO scope.

| Metric (ms) | p50 | p95 | max |
| --- | --- | --- | --- |
| main thread until `initialize()` suspends | 0.256 | 2.973 | 4.969 |
| until the init result is ready (wall, mostly off the main thread) | 92.3 | 166.2 | 178.3 |

## Memory (PSS, `dumpsys meminfo` TOTAL PSS, KiB)

| Moment | Without SDK median (min - max) | With SDK median (min - max) | Delta median (paired min - max) |
| --- | --- | --- | --- |
| 5 s after start (n=20) | 18002.5 (17946 - 18064) | 22020 (21586 - 22502) | 4014 (3576 to 4507) |
| 5 s after 500 track() calls (n=10) | 18010.5 (17973 - 18087) | 34607.5 (33736 - 35269) | 16532.5 (15671 to 17296) |

The endpoint was `http://127.0.0.1:9` (nothing answers), so the SDK queued events and its delivery attempts failed, as in an app that is offline. The baseline burst run logs and does nothing else. The min and max columns show the spread between runs.

Raw data: `apk-size.csv`, `startup.csv`, `init.csv`, `pss-burst.csv`, `dependencies.txt`, `meta.json`.
