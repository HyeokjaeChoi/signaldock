# iOS SDK cost for a host app (ticket 16)

Measured on a Simulator, not on a physical device: iPhone 18 Pro (iOS 27.0) on Apple M2 Pro, macOS 26.6.2, Xcode 27.1 Build version 27A9275. One shared host (load average in `meta.json`: start [452.14, 363.64, 230.97], end [198.47, 202.4, 213.39]). Numbers show the order of magnitude, not an SLA. Method and limits: ADR-0047 amendment "ticket 16, measurement method" and ADR-0020.

Both arms are the independent consumer app (`compatibility/consumer-ios`, Release, Swift 6 mode). The SDK arm uses the SDK from the release ZIP `signaldock-sdk-ios-0.1.0.zip` of `0.1.0`. The baseline arm uses an empty package with the same public signatures (`perf/ios-host-cost/baseline`), so the app sources are identical and the baseline binary holds no SDK code. Run 2026-10-29T01:25:20Z, git e39f8da (dirty tree).

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
| process start to first view | 414.0 / 745.9 / 1072.0 | 418.2 / 471.6 / 930.8 |
| `simctl launch` wall time | 163.9 / 1202.4 / 1640.5 | 164.6 / 478.5 / 575.2 |

Paired difference (with minus without, run by run), process start to first view: median 1.1 ms, min -600.4, max 499.4. `simctl launch` wall time: median -1.5 ms, min -1065.3, max 74.1.

## `initialize()` and `track()` on the main thread (SDK arm)

Main-thread time follows ADR-0020: from the call until the calling task first suspends (a sentinel task queued on the main actor before the call runs at that moment). It includes call setup and the class loading the call causes, so it is an upper bound for the SDK alone. `wall` runs until the result is ready (mostly off the main thread).

| Call | n | main p50 / p95 / max, ms | wall p50 / p95 / max, ms |
| --- | --- | --- | --- |
| `SignalDockSDK.initialize` (cold start) | 20 | 17.805 / 25.935 / 26.743 | 32.423 / 70.452 / 106.387 |
| `track` (`productViewed`, 500 calls per run, 10 runs, pooled) | 5000 | 0.038 / 0.127 / 1.824 | 0.417 / 23.049 / 1016.353 |

The first `track` call of each burst run (main thread): p50 1.203 ms, max 1.824 ms. `track()` runs on the caller's actor (the main actor here) until it hands the work to the SDK; the numbers are the main-actor part of one call. Samples: `track-samples.csv`.

## Memory (`footprint`, physical footprint of the app process, MiB)

| Moment | Without SDK median (min - max) | With SDK median (min - max) | Delta median (paired min - max) |
| --- | --- | --- | --- |
| 5s after start (n=20) | 21.34 (21.24 - 23.64) | 21.71 (21.46 - 21.96) | +0.37 (-1.80 to +0.67) |
| 5s after 500 `track()` calls (n=10) | 21.51 (21.35 - 23.11) | 22.16 (21.86 - 24.71) | +0.62 (-1.20 to +3.19) |

The endpoint was `http://127.0.0.1:9` (nothing answers) with a dummy key, so the SDK queued events and its delivery attempts failed, as in an app that is offline; no event left the Mac. The baseline arm's `track()` does nothing. Memory is the Simulator host process view (`footprint -j`), a sample of a moving value: read differences of the order of the spread or more.

Raw data: `size.csv`, `sdk-sections.csv`, `link-objects.csv`, `dependencies.txt`, `startup.csv`, `init.csv`, `track-samples.csv`, `memory-burst.csv`, `meta.json`.
