# SDK cost for a host app (issue 36)

Measured on a physical device (a personal phone, ADR-0042): SM-F766N (physical) (API 37, Android 17, SM-F766N, arm64-v8a) on Apple M2 Pro, one shared host (load average in `meta.json`). Numbers show the order of magnitude, not an SLA. Method and limits: ADR-0020.

Both APKs are the independent consumer app (`compatibility/consumer-android`), R8 release, built from the Maven ZIP of `local.signaldock:sdk-android:0.3.0`. The baseline is built with `-Psignaldock.sdk=false`: no SDK dependency, no SDK call, and no coroutines library. Both ran interleaved on the same phone. Run 2026-10-24T13:54:25Z, git 049f7ed (dirty).

## APK size (R8 release, R8 9.3.28)

| | Without SDK | With SDK | Delta |
| --- | --- | --- | --- |
| APK bytes | 21606 | 645055 | +623449 (2885.5 %) |
| Download size (apkanalyzer) | 17263 | 571991 | +554728 (3213.4 %) |
| dex bytes (1 / 1 files, sum of classes*.dex) | 3012 | 986140 | +983128 |
| dex bytes in the APK (compressed) | 1591 | 471083 | +469492 |

The baseline is a tiny app (two classes), so the percentage is large and says little; the byte delta is what an app adds. Resources are not shrunk (the consumer has no `shrinkResources`). Libraries that come with the SDK are in `dependencies.txt`: the POM lists 7 direct dependencies, and 49 libraries are new on the release runtime classpath (Room, WorkManager, OkHttp, Retrofit, kotlinx.serialization, coroutines and their dependencies).

## Startup (20 cold starts per variant, interleaved; 2 warm-up launches not counted)

| Metric (ms) | Without SDK p50 / p95 / max | With SDK p50 / p95 / max | Unit |
| --- | --- | --- | --- |
| `am start -W` TotalTime | 82.5 / 111 / 117 | 88.5 / 116 / 124 | ms |

Paired difference of TotalTime (with minus without, run by run): median 7 ms, min -25, max 47.

Main-thread time in `SignalDock.initialize()` (consumer app, `SystemClock.elapsedRealtimeNanos()`, n=20): `initialize()` is called from `Application.onCreate` in a coroutine that starts undispatched on the main thread; the time until it first suspends is the main-thread cost (it includes the coroutine setup). The SDK does its work on its own IO scope.

| Metric (ms) | p50 | p95 | max |
| --- | --- | --- | --- |
| main thread until `initialize()` suspends | 0.189 | 0.227 | 0.240 |
| until the init result is ready (wall, mostly off the main thread) | 33.2 | 45.6 | 62.1 |

## Memory (PSS, `dumpsys meminfo` TOTAL PSS, KiB)

| Moment | Without SDK median (min - max) | With SDK median (min - max) | Delta median (paired min - max) |
| --- | --- | --- | --- |
| 5 s after start (n=20) | 40693 (16794 - 40921) | 56069.5 (55416 - 56855) | 15548.5 (14613 to 38958) |
| 5 s after 500 track() calls (n=10) | 40720 (40651 - 40892) | 68299 (67415 - 70464) | 27495.5 (26749 to 29672) |

The endpoint was `http://127.0.0.1:9` (nothing answers), so the SDK queued events and its delivery attempts failed, as in an app that is offline. The baseline burst run logs and does nothing else. The min and max columns show the spread between runs.

Raw data: `apk-size.csv`, `startup.csv`, `init.csv`, `pss-burst.csv`, `dependencies.txt`, `meta.json`.
