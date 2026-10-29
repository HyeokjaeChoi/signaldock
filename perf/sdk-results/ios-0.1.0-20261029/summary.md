# iOS SDK cost for a host app (ticket 16)

Measured on a Simulator, not on a physical device: iPhone 18 Pro (iOS 27.0) on Apple M2 Pro, macOS 26.6.2, Xcode 27.1 Build version 27A9275. One shared host (load average in `meta.json`: start [73.28, 49.04, 81.59], end [6.04, 34.63, 67.03]). **The host load was high** (1-minute load average up to 168 on 12 CPUs at the launches): p95 and max are not meaningful and are shown only for completeness. Read the medians and the paired median. Numbers show the order of magnitude, not an SLA. Method and limits: ADR-0047 amendment "ticket 16, measurement method" and ADR-0020.

Both arms are the independent consumer app (`compatibility/consumer-ios`, Release, Swift 6 mode). The SDK arm uses the SDK from the release ZIP `signaldock-sdk-ios-0.1.0.zip` of `0.1.0`. The baseline arm uses an empty package with the same public signatures (`perf/ios-host-cost/baseline`), so the app sources are identical and the baseline binary holds no SDK code. Run 2026-10-29T02:05:50Z, git 7b1b62d.

## Size (`xcodebuild archive`, Release, iphoneos arm64, unsigned, stripped by the archive step)

| | Without SDK | With SDK | Delta |
| --- | --- | --- | --- |
| .app folder, bytes (sum of file sizes) | 165,581 | 557,194 | +391,613 |
| Main binary `ConsumerIOS`, bytes | 164,560 | 553,024 | +388,464 |
| `.ipa` form, bytes (unencrypted zip, level 9) | 49,263 | 202,566 | +153,303 |
| SDK resource bundle `SignalDock_SignalDock.bundle`, bytes | 0 | 3,149 | +3,149 |
| `__TEXT` segment (page aligned), bytes | 98,304 | 409,600 | +311,296 |
| `__DATA_CONST` segment, bytes | 16,384 | 32,768 | +16,384 |
| `__DATA` segment, bytes | 16,384 | 49,152 | +32,768 |
| `__LINKEDIT` segment, bytes | 49,152 | 81,920 | +32,768 |
| `__TEXT,__text` section (code), bytes | 60,944 | 287,240 | +226,296 |
| Link map: file-backed bytes of the package object `SignalDock.o` (zero-fill excluded) | 4,519 | 300,603 | +296,084 |
| Link map: file-backed bytes of all other objects | 81,032 | 101,323 | +20,291 |

The baseline package object is the empty stand-in. The SDK is linked statically into the main binary (`SignalDock.o`); the app has no `Frameworks` folder, so no embedded or third-party dynamic library is added. The SDK makes the main binary link 3 more system libraries (they add no bytes to the app): `CoreFoundation`, `UIKit`, `libsqlite3.dylib` (`dependencies.txt`). Dead code stripping is on, so unused SDK code is not in these numbers. The other link-map objects grow by +20,291 bytes together; the largest changes are `linker synthesized` +8,654, `ConsumerApp.o` +4,699, `libswiftCore.tbd` +2,420. `linker synthesized` holds stubs and other linker-made data. These bytes are part of the main binary delta and not of `SignalDock.o`. The `.ipa` here is not encrypted; an App Store build encrypts `__TEXT`, which compresses worse, so a store download is larger than this figure. Per-section and per-object data: `sdk-sections.csv`, `link-objects.csv`.

## Startup (20 cold starts per arm, interleaved; 2 warm-up launches not counted)

Cold start = `simctl launch --terminate-running-process`, then the app writes the time from the kernel process start record to the first `onAppear` of the root view. `launch wall` is the time of the `xcrun simctl launch` call itself.

| Metric (ms) | Without SDK p50 / p95 / max | With SDK p50 / p95 / max |
| --- | --- | --- |
| process start to first view | 416.3 / 473.8 / 543.6 | 413.2 / 462.7 / 824.4 |
| `simctl launch` wall time | 160.9 / 269.8 / 434.9 | 162.3 / 281.1 / 723.2 |

Paired difference (with minus without, run by run), process start to first view: median -1.3 ms, min -107.0, max 400.5. `simctl launch` wall time: median 1.6 ms, min -201.6, max 486.3.

## `initialize()` and `track()` on the main thread (SDK arm)

What "main" measures: the time on the main actor from the call until the calling task first suspends, or until the call returns if it never suspends (never more than wall). A sentinel task is queued on the main actor right before the call and runs when the caller first suspends. For `initialize()` the probe first waits for the first view plus a 500 ms settle time, so the main-actor queue is idle and the sentinel does not wait behind SwiftUI work; the floor row is the same probe around the empty stand-in and shows the cost of the method itself. This is the quantity of ADR-0020 item 2 (main-thread time until the first suspension). It includes call setup and the class loading the call causes, so it is an upper bound for the SDK alone. `wall` runs until the result is ready (mostly off the main thread).

| Call | n | main p50 / p95 / max, ms | wall p50 / p95 / max, ms |
| --- | --- | --- | --- |
| `SignalDockSDK.initialize` (cold start) | 20 | 0.056 / 0.225 / 0.226 | 25.460 / 150.593 / 161.292 |
| same probe around the empty stand-in `initialize` (floor of the method) | 20 | 0.013 / 0.021 / 0.027 | 0.013 / 0.021 / 0.027 |
| `track` (`productViewed`, 500 calls per run, 10 runs, pooled) | 5000 | 0.035 / 0.052 / 2.440 | 0.386 / 0.668 / 13.224 |

The first `track` call of each burst run (main thread): p50 1.473 ms, max 2.440 ms. `track()` runs on the caller's actor (the main actor here) until it hands the work to the SDK; the numbers are the main-actor part of one call. Samples: `track-samples.csv`.

## Memory (`footprint`, physical footprint of the app process, MiB)

| Moment | Without SDK median (min - max) | With SDK median (min - max) | Delta median (paired min - max) |
| --- | --- | --- | --- |
| 5s after launch (n=20) | 21.49 (21.38 - 21.72) | 21.92 (21.85 - 22.05) | +0.42 (+0.31 to +0.61) |
| 5s after 500 `track()` calls (n=10) | 21.38 (21.28 - 21.41) | 21.84 (21.66 - 22.21) | +0.45 (+0.28 to +0.84) |

The endpoint was `http://127.0.0.1:9` (nothing answers) with a dummy key, so the SDK queued events and its delivery attempts failed, as in an app that is offline; no event left the Mac. The baseline arm's `track()` does nothing. Memory is the Simulator host process view (`footprint -j`), a sample of a moving value: read differences of the order of the spread or more.

Raw data: `size.csv`, `sdk-sections.csv`, `link-objects.csv`, `dependencies.txt`, `startup.csv`, `init.csv`, `track-samples.csv`, `memory-burst.csv`, `meta.json`.
