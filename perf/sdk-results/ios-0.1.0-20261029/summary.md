# iOS SDK cost for a host app (ticket 16)

Measured on a Simulator, not on a physical device: iPhone 18 Pro (iOS 27.0) on Apple M2 Pro, macOS 26.6.2, Xcode 27.1 Build version 27A9275. One shared host (load average in `meta.json`: start [81.07, 41.18, 51.85], end [4.28, 31.29, 48.41]). **The host load was high** (1-minute load average up to 260 on 12 CPUs at the launches): p95 and max are not meaningful and are shown only for completeness. Read the medians and the paired median. Numbers show the order of magnitude, not an SLA. Method and limits: ADR-0047 amendment "ticket 16, measurement method" and ADR-0020.

Both arms are the independent consumer app (`compatibility/consumer-ios`, Release, Swift 6 mode). The SDK arm uses the SDK from the release ZIP `signaldock-sdk-ios-0.1.0.zip` of `0.1.0`. The baseline arm uses an empty package with the same public signatures (`perf/ios-host-cost/baseline`), so the app sources are identical and the baseline binary holds no SDK code. Run 2026-10-29T02:31:07Z, git 91aece9.

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
| Link map: attributed symbol bytes of the package object `SignalDock.o` (file-backed) | 4,519 | 300,603 | +296,084 |
| Link map: attributed symbol bytes of all other objects | 81,032 | 101,323 | +20,291 |
| Main binary bytes not attributed to a symbol (headers, load commands, padding, `__LINKEDIT`, section gaps) | 79,009 | 151,098 | +72,089 |

The baseline package object is the empty stand-in. The SDK is linked statically into the main binary (`SignalDock.o`); the app has no `Frameworks` folder, so no embedded or third-party dynamic library is added. The SDK makes the main binary link 3 more system libraries (their binaries are not embedded in the app, but the references and stubs to them make the main binary larger): `CoreFoundation`, `UIKit`, `libsqlite3.dylib` (`dependencies.txt`). Dead code stripping is on, so unused SDK code is not in these numbers. The other link-map objects grow by +20,291 bytes together; the largest changes are `linker synthesized` +8,654, `ConsumerApp.o` +4,699, `libswiftCore.tbd` +2,420. `linker synthesized` holds stubs and other linker-made data. These bytes are part of the main binary delta and not of `SignalDock.o`. The `.ipa` here is not encrypted; an App Store build encrypts `__TEXT`, which compresses worse, so a store download is larger than this figure. Per-section and per-object data: `sdk-sections.csv`, `link-objects.csv`.

## Startup (20 cold starts per arm, interleaved; 2 warm-up launches not counted)

Cold start launches (the 20 runs of this table): the app is the measurement build with `initialize()` at its shipped place, `AppModel.init`, before the first view (the probe adds no delay in these launches). Each run is `simctl launch --terminate-running-process`; the app writes the time from the kernel process start record to the first `onAppear` of the root view, so the SDK arm's `initialize()` work that runs before the first view is inside the figure. `launch wall` is the time of the `xcrun simctl launch` call itself. Memory after launch is read in these launches too. `initialize()` result-ready (wall) time in these launches: SDK arm median 30.8 ms, p95 81.8 ms (`startup.csv`, `init_wall_ms`).

| Metric (ms) | Without SDK p50 / p95 / max | With SDK p50 / p95 / max |
| --- | --- | --- |
| process start to first view | 413.7 / 438.0 / 1179.9 | 413.9 / 440.9 / 499.4 |
| `simctl launch` wall time | 159.3 / 248.9 / 981.7 | 160.2 / 236.5 / 1115.7 |

Paired difference (with minus without, run by run), process start to first view: median 1.1 ms, min -739.0, max 80.1. `simctl launch` wall time: median 2.1 ms, min -745.3, max 866.8.

## `initialize()` and `track()` on the main thread (SDK arm)

Settled launches (separate launches, 20 per arm, interleaved; they are not used for cold start or memory after launch): the probe waits for the first view and a 500 ms settle time, then calls `initialize()`, so the main-actor queue is idle. "main" is the time on the main actor from the call until the calling task first suspends, or until the call returns if it never suspends (never more than wall). A sentinel task is queued on the main actor right before the call and runs when the caller first suspends. In the cold-start launches the sentinel would wait behind SwiftUI work, which is why they are not used for this figure. This is the quantity of ADR-0020 item 2. It includes call setup and the class loading the call causes, so it is an upper bound for the SDK alone. The floor row is the same probe around the empty stand-in. The `track()` rows come from the burst launches, which use the same settled mode (idle queue). `wall` runs until the result is ready (mostly off the main thread).

| Call | n | main p50 / p95 / max, ms | wall p50 / p95 / max, ms |
| --- | --- | --- | --- |
| `SignalDockSDK.initialize` (settled launches) | 20 | 0.051 / 0.094 / 0.107 | 22.988 / 28.713 / 32.140 |
| same probe around the empty stand-in `initialize` (floor of the method) | 20 | 0.015 / 0.023 / 0.025 | 0.015 / 0.023 / 0.025 |
| `track` (`productViewed`, 500 calls per run, 10 runs, pooled) | 5000 | 0.035 / 0.052 / 2.867 | 0.373 / 0.683 / 20.243 |

The first `track` call of each burst run (main thread): p50 1.777 ms, max 2.867 ms. `track()` runs on the caller's actor (the main actor here) until it hands the work to the SDK; the numbers are the main-actor part of one call. Samples: `track-samples.csv`.

## Memory (`footprint`, physical footprint of the app process, MiB)

| Moment | Without SDK median (min - max) | With SDK median (min - max) | Delta median (paired min - max) |
| --- | --- | --- | --- |
| 5s after launch (n=20) | 21.34 (21.08 - 21.53) | 21.69 (21.50 - 24.31) | +0.38 (+0.16 to +2.78) |
| 5s after 500 `track()` calls (n=10) | 21.32 (21.14 - 21.42) | 21.75 (21.61 - 22.06) | +0.53 (+0.38 to +0.73) |

The endpoint was `http://127.0.0.1:9` (nothing answers) with a dummy key, so the SDK queued events and its delivery attempts failed, as in an app that is offline; no event left the Mac. The baseline arm's `track()` does nothing. Memory is the Simulator host process view (`footprint -j`), a sample of a moving value: read differences of the order of the spread or more.

Raw data: `size.csv`, `sdk-sections.csv`, `link-objects.csv`, `dependencies.txt`, `startup.csv`, `init.csv`, `track-samples.csv`, `memory-burst.csv`, `meta.json`.
