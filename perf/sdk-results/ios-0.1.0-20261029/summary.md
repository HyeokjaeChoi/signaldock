# iOS SDK cost for a host app (ticket 16)

Measured on a Simulator, not on a physical device: iPhone 18 Pro (iOS 27.0) on Apple M2 Pro, macOS 26.6.2, Xcode 27.1 Build version 27A9275. One shared host (load average in `meta.json`: start [87.64, 91.05, 142.98], end [6.96, 83.45, 132.41]). Numbers show the order of magnitude, not an SLA. Method and limits: ADR-0047 amendment "ticket 16, measurement method" and ADR-0020.

Both arms are the independent consumer app (`compatibility/consumer-ios`, Release, Swift 6 mode). The SDK arm uses the SDK from the release ZIP `signaldock-sdk-ios-0.1.0.zip` of `0.1.0`. The baseline arm uses an empty package with the same public signatures (`perf/ios-host-cost/baseline`), so the app sources are identical and the baseline binary holds no SDK code. Run 2026-10-29T01:43:11Z, git 77151d4.

## Size (`xcodebuild archive`, Release, iphoneos arm64, unsigned, stripped by the archive step)

| | Without SDK | With SDK | Delta |
| --- | --- | --- | --- |
| .app folder, bytes (sum of file sizes) | 164,917 | 556,698 | +391,781 |
| Main binary `ConsumerIOS`, bytes | 163,896 | 552,528 | +388,632 |
| `.ipa` form, bytes (unencrypted zip, level 9) | 47,534 | 201,007 | +153,473 |
| SDK resource bundle `SignalDock_SignalDock.bundle`, bytes | 0 | 3,149 | +3,149 |
| `__TEXT` segment (page aligned), bytes | 98,304 | 409,600 | +311,296 |
| `__DATA_CONST` segment, bytes | 16,384 | 32,768 | +16,384 |
| `__DATA` segment, bytes | 16,384 | 49,152 | +32,768 |
| `__LINKEDIT` segment, bytes | 49,152 | 81,920 | +32,768 |
| `__TEXT,__text` section (code), bytes | 58,500 | 284,660 | +226,160 |
| Link map: bytes of the package object `SignalDock.o` | 5,031 | 318,667 | +313,636 |
| Link map: bytes of the app objects (ConsumerApp.o and others) | 79,517 | 99,644 | +20,127 |

The baseline package object is the empty stand-in. The SDK is linked statically into the main binary (`SignalDock.o`); no `Frameworks` folder is added and no dynamic library is added (`dependencies.txt`). Dead code stripping is on, so unused SDK code is not in these numbers. The app objects (mostly `ConsumerApp.o`) also differ, because the app code that calls the real SDK types is larger than the code that calls the empty stand-in; this part belongs to the cost of calling the SDK and is counted in the main binary delta, not in `SignalDock.o`. The `.ipa` here is not encrypted; an App Store build encrypts `__TEXT`, which compresses worse, so a store download is larger than this figure. Per-section and per-object data: `sdk-sections.csv`, `link-objects.csv`.

## Startup (20 cold starts per arm, interleaved; 2 warm-up launches not counted)

Cold start = `simctl terminate`/`launch`, then the app writes the time from the kernel process start record to the first `onAppear` of the root view. `launch wall` is the time of the `xcrun simctl launch` call itself.

| Metric (ms) | Without SDK p50 / p95 / max | With SDK p50 / p95 / max |
| --- | --- | --- |
| process start to first view | 411.5 / 1556.1 / 1880.6 | 414.2 / 442.6 / 478.1 |
| `simctl launch` wall time | 161.7 / 1187.6 / 1720.1 | 166.9 / 898.4 / 944.1 |

Paired difference (with minus without, run by run), process start to first view: median -4.7 ms, min -1444.8, max 26.1. `simctl launch` wall time: median -3.5 ms, min -1358.8, max 207.0.

## `initialize()` and `track()` on the main thread (SDK arm)

Main-thread time follows ADR-0020: from the call until the calling task first suspends (a sentinel task queued on the main actor before the call runs at that moment). It includes call setup, the class loading the call causes, and any other main-actor job that is already queued ahead of the sentinel at that moment (at startup SwiftUI work is), so it is an upper bound for the SDK alone; the floor row shows what the same probe reads around an empty call. A `track()` call later in the run has no queue ahead of it. `wall` runs until the result is ready (mostly off the main thread).

| Call | n | main p50 / p95 / max, ms | wall p50 / p95 / max, ms |
| --- | --- | --- | --- |
| `SignalDockSDK.initialize` (cold start) | 20 | 9.987 / 23.029 / 24.494 | 30.495 / 2296.099 / 3426.169 |
| same probe around the empty stand-in `initialize` (floor of the method) | 20 | 8.665 / 10.334 / 10.700 | 0.005 / 0.008 / 0.021 |
| `track` (`productViewed`, 500 calls per run, 10 runs, pooled) | 5000 | 0.033 / 0.044 / 7.307 | 0.363 / 0.599 / 11.829 |

The first `track` call of each burst run (main thread): p50 1.135 ms, max 7.307 ms. `track()` runs on the caller's actor (the main actor here) until it hands the work to the SDK; the numbers are the main-actor part of one call. Samples: `track-samples.csv`.

## Memory (`footprint`, physical footprint of the app process, MiB)

| Moment | Without SDK median (min - max) | With SDK median (min - max) | Delta median (paired min - max) |
| --- | --- | --- | --- |
| 5s after start (n=20) | 21.26 (21.10 - 23.47) | 21.76 (21.64 - 24.16) | +0.52 (-1.67 to +2.95) |
| 5s after 500 `track()` calls (n=10) | 21.35 (21.28 - 21.58) | 22.02 (21.86 - 22.27) | +0.69 (+0.28 to +0.89) |

The endpoint was `http://127.0.0.1:9` (nothing answers) with a dummy key, so the SDK queued events and its delivery attempts failed, as in an app that is offline; no event left the Mac. The baseline arm's `track()` does nothing. Memory is the Simulator host process view (`footprint -j`), a sample of a moving value: read differences of the order of the spread or more.

Raw data: `size.csv`, `sdk-sections.csv`, `link-objects.csv`, `dependencies.txt`, `startup.csv`, `init.csv`, `track-samples.csv`, `memory-burst.csv`, `meta.json`.
