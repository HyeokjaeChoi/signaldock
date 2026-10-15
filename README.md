# SignalDock

[한국어](README.ko.md)

An Android event SDK designed to save events on the device and confirm server receipt through an ACK after commit.

This monorepo contains the SDK, a local data processing server, and their design.
Review the path from an app's `track()` call through a durable queue, retries, server deduplication, and independent artifact installation.

> **Status: 2026-10-14**
> The first release scope is done. The SDK, the server, the Android demo app, and the Dashboard are implemented. Delivery failure E2E, device durability, contract and DB change checks, server load measurement, and SDK device measurement have scripts and recorded results ([issues 20 to 24](.scratch/signaldock-portfolio/issues/)). [Reproduce from a clean clone](#reproduce-from-a-clean-clone) lists the commands that were run in a fresh clone, and what was not verified: a physical device, and the full server measurement matrix (not run, Q133).

![The Dashboard with one stored event selected: the event list on the left, the event details and stored JSON on the right.](docs/images/dashboard.png)

![The benchmark report: verdict, collection latency, and the 503 share of one overload run.](docs/images/perf-report.png)

The screenshots show the Dashboard and the benchmark report with 120 seeded test events and the recorded runs in `perf/results/`.
A short video of demo `track()` to Dashboard receipt: [25-demo-track-to-dashboard.mp4](.scratch/signaldock-portfolio/evidence/25-demo-track-to-dashboard.mp4).

## Start here

1. [Architecture](architecture.md): component responsibilities, storage ownership, and recovery boundaries.
2. [Architecture SVG](docs/architecture/signaldock-architecture.svg): a static view of the onepager's structure.
3. [Architecture onepager HTML](docs/architecture/signaldock-onepager.html): diagrams, operation sequence, and design Q&A.
4. [Installation identity ADR](docs/adr/0001-installation-scoped-identity.md) and [OpenAPI / JSON ADR](docs/adr/0002-openapi-json-contract.md): decisions and tradeoffs.
5. [First release spec](.scratch/signaldock-portfolio/spec.md): input, ACK, error, lifecycle, and verification contracts.

Download the HTML and open it in a local browser. GitHub's file view does not run the page.
Keep the repository's folder structure so the supporting document links work.

On macOS, run this from the repository root to preview the document:

```sh
open docs/architecture/signaldock-onepager.html
```

![SignalDock structure: the demo app calls the SDK API, Room stores events, and a shared sender sends them to Ktor. The Dashboard queries server records.](docs/architecture/signaldock-architecture.svg)

## What to review

An SDK's success response tells the host app what it can do next.
SignalDock separates durable device storage from server receipt, with recovery rules for each stage.
This portfolio for mobile SDK engineer roles covers public API design, lifecycle management, recovery, compatibility, and independent distribution.

| Design concern | Decision | Recorded evidence |
| --- | --- | --- |
| Offline collection within a storage limit | Collection succeeds at Room commit. At the queue limit, preserve existing events and return `QueueFull` for new calls | 1,000 offline events survived a process restart; concurrent inserts and capacity boundaries ran on two emulators ([issue 20](.scratch/signaldock-portfolio/issues/20-sdk-durability-device.md)) |
| Lost ACKs without duplicate storage | Retry with the same `eventId`. Use a server unique constraint and content comparison; ACK after commit | A fault proxy dropped the response after commit; duplicate and conflict results; unique rows and counts compared ([issue 21](.scratch/signaldock-portfolio/issues/21-delivery-failure-e2e.md)) |
| Foreground response and background recovery | Share the sender and durable retry state with WorkManager. Keep collection writes separate from the send lock | Sender contention, cancellation, Worker recovery, and retry waits across restart ran on two emulators ([issue 20](.scratch/signaldock-portfolio/issues/20-sdk-durability-device.md)) |
| Shared contracts and independent releases | OpenAPI / JSON with generated source drift checks. Install the Maven ZIP in a separate R8 consumer app | Round trips, earlier-version fixtures, and DB migrations ([issue 22](.scratch/signaldock-portfolio/issues/22-contract-db-change-checks.md)); Maven ZIP installation and an R8 release consumer app ([issue 06](.scratch/signaldock-portfolio/issues/06-distribution.md), [ADR-0009](docs/adr/0009-maven-zip-and-consumer-app.md)) |

## Event flow

1. After initialization succeeds, the host app calls `track()`. Collection succeeds after input validation and Room storage.
2. The shared sender checks the earliest retry time and sends a batch. It holds no Room transaction open during HTTP.
3. The server validates each event, handles duplicates, and returns an ACK after PostgreSQL commit.
4. The SDK validates the entire ACK before it applies queue changes atomically. An ambiguous ACK leaves the whole batch in the queue.
5. App diagnostics show the device queue and recent ACK. The Dashboard shows stored server events and their details.

A lost ACK does not change the event ID. The same ID and content produce duplicate success; different content produces a conflict rejection.
Deduplication lasts while the server row exists. The design does not claim exactly-once delivery.

## Stack

| Area | Choice |
| --- | --- |
| Android SDK | Kotlin · minSdk 24 · Coroutines · Room · Retrofit / OkHttp · kotlinx.serialization · WorkManager |
| Demo app | Jetpack Compose · ViewModel. The SDK has no dependency on this UI stack |
| Server | Kotlin / Ktor · PostgreSQL · jOOQ · JDBC / HikariCP · Flyway |
| Verification Dashboard | React · TypeScript · Vite · Recharts |
| Contracts and distribution | OpenAPI / JSON · generated sources tracked in Git · Docker Compose entry point · local Maven repository ZIP |

Exact versions are pinned in `gradle/libs.versions.toml`. [TOOLCHAIN.md](TOOLCHAIN.md) gives the reason for each pin and the build that verified it.
The first coordinate is `local.signaldock:sdk-android:0.1.0` (local only, not Maven Central). Build the ZIP as described below.

## Scope and verification

The first release covers a native Android SDK and one local project.
The demo follows product view, cart, and simulated purchase. The Dashboard helps verify SDK integration.
iOS / Flutter, React Native / Flutter / Unity or WebView (native-web) bridges (Q150), automatic events, `identify`, campaign / push, funnels, multiple accounts, public internet operation, and Maven Central are outside this release.

The [completion criteria](.scratch/signaldock-portfolio/spec.md#7-completion-criteria-and-verification-plan) require environment records, source revision/hash, and event ID comparisons. Each implementation issue in `.scratch/signaldock-portfolio/issues/` records what ran and what did not.
Measured results are local environment records, not an SLA:

- Server: two pilot runs and one overload run on one Mac with Docker limits, in [`perf/results/`](perf/results/). The overload run shows HTTP 503 with `Retry-After` and recovery without a restart. The 216-run pool-size matrix was not run (Q133, [ADR-0013](docs/adr/0013-server-load-measurement-harness.md), [issue 23](.scratch/signaldock-portfolio/issues/23-server-load-measurement.md)).
- SDK: normal, offline, and burst runs on API 24 and API 37 emulators with a debug build, and once more on the API 37 emulator with an R8 release build (debug-signed), in [`perf/sdk-results/summary.md`](perf/sdk-results/summary.md) ([ADR-0014](docs/adr/0014-sdk-device-measurement-harness.md), [ADR-0018](docs/adr/0018-sdk-release-r8-measurement.md), [issue 24](.scratch/signaldock-portfolio/issues/24-sdk-device-measurement.md), [issue 33](.scratch/signaldock-portfolio/issues/33-sdk-crash-safety-and-release-build.md)).
- SDK cost for a host app, on the API 37 emulator with the R8 consumer app with and without the SDK: the APK grows by 616,829 bytes (dex by 966,108 bytes), cold start `TotalTime` p50 is 235 ms without and 238 ms with the SDK, `initialize()` takes 0.26 ms (p50) and 3.0 ms (p95) of the main thread, and PSS is 4.0 MiB higher after 5 s idle ([`perf/sdk-results/host-cost-api37/summary.md`](perf/sdk-results/host-cost-api37/summary.md), [ADR-0020](docs/adr/0020-sdk-host-app-cost.md), [issue 36](.scratch/signaldock-portfolio/issues/36-sdk-host-app-cost.md)).
- The [performance plan](.scratch/signaldock-portfolio/performance-plan.md) holds the original targets and conditions.

Steps to run, stop, and reset the stack, to build the Maven ZIP, and to run the consumer app are below.

## Reproduce from a clean clone

The commands in this section were run in a fresh clone, except `docker compose down --volumes` (the reset), which was not run on purpose. The record (commands, results, wall times) is in [issue 25](.scratch/signaldock-portfolio/issues/25-reproduction-readme-video.md). The longer guide (demo app, E2E, device scripts, measurements, physical device, reset, limits) is [docs/reproduce.md](docs/reproduce.md).
A short video of demo `track()` to Dashboard receipt is in [`.scratch/signaldock-portfolio/evidence/25-demo-track-to-dashboard.mp4`](.scratch/signaldock-portfolio/evidence/25-demo-track-to-dashboard.mp4).
The first run downloads the JDK, Docker images, Gradle dependencies, and npm packages. It is not fully offline.

You need: macOS or Linux, Docker with Compose, Python 3, `curl`, `openssl`, and an Android SDK with platform `android-36` (`ANDROID_HOME`). The device scripts also need an emulator (AVD). `scripts/device-measure.sh` also needs macOS.

### 1. Toolchain and hooks

```sh
git clone https://github.com/HyeokjaeChoi/signaldock.git && cd signaldock
git config core.hooksPath .githooks     # once per clone
scripts/setup-toolchain.sh              # JDK 21, oasdiff, OpenAPI generator -> .tools/
```

Versions and hashes: [TOOLCHAIN.md](TOOLCHAIN.md). The Gradle scripts use `.tools/jdk-21.0.12.1+1` when `JAVA_HOME` is not set.

### 2. Server, PostgreSQL, and Dashboard (Compose)

Create `.env` and give each secret a random value. The two keys must differ (the server refuses to start otherwise). Never commit `.env`.

```sh
cp .env.example .env
for v in SIGNALDOCK_COLLECTION_KEY SIGNALDOCK_QUERY_KEY SIGNALDOCK_DB_PASSWORD; do
  sed -i.bak "s|^$v=.*|$v=$(openssl rand -hex 24)|" .env
done
rm .env.bak
chmod 600 .env                          # owner-only: the file holds keys and a password
docker compose up -d --build            # PostgreSQL, server, Dashboard
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:5173/   # 200 = Dashboard is up
```

- Dashboard: `http://127.0.0.1:5173` (`SIGNALDOCK_DASHBOARD_PORT`). Host server endpoint: `http://127.0.0.1:8080`. The server container binds to `0.0.0.0:8080`, and Compose publishes every host port on `127.0.0.1` only.
- Apps send events with `SIGNALDOCK_COLLECTION_KEY`. The Dashboard holds no key: nginx proxies `GET /v1/*` and adds `SIGNALDOCK_QUERY_KEY` ([ADR-0008](docs/adr/0008-dashboard-and-compose-delivery.md)).
- Development mode: `docker compose up -d db`, then run the server from `server/` with the variables in `.env.example` and `npm run dev` in `dashboard/`.

### 3. SDK Maven ZIP and the independent consumer app

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk"     # your Android SDK path
scripts/verify-consumer.sh
```

The script builds `signaldockMavenZip`, unzips it into a clean temporary folder, and builds `compatibility/consumer-android/` from that folder only, by the coordinate `local.signaldock:sdk-android:0.1.0`, as an R8 release. It checks the AAR Kotlin metadata version and prints the ZIP, folder, and APK paths. To keep the folder, set `SIGNALDOCK_REPO_DIR` to a new empty path first. The ZIP alone: `sdk/gradlew -p sdk :sdk-android:signaldockMavenZip --no-daemon` (output `sdk/sdk-android/build/distributions/signaldock-sdk-android-0.1.0-maven.zip`). See [ADR-0009](docs/adr/0009-maven-zip-and-consumer-app.md).

Now run the R8 release APK on an emulator and let it reach the server. The collection key is baked in at build time:

```sh
SIGNALDOCK_REPO_DIR=/tmp/signaldock-maven scripts/verify-consumer.sh   # new or empty folder
set -a; . ./.env; set +a
compatibility/consumer-android/gradlew -p compatibility/consumer-android \
  -Psignaldock.repo=/tmp/signaldock-maven -Psignaldock.collectionKey="$SIGNALDOCK_COLLECTION_KEY" :app:assembleRelease
"$ANDROID_HOME/emulator/emulator" -avd signaldock-api37 -no-window -no-audio -no-boot-anim -no-snapshot-save -port 5584 &
adb -s emulator-5584 install -r compatibility/consumer-android/app/build/outputs/apk/release/app-release.apk
adb -s emulator-5584 shell am start -n local.signaldock.consumer/.MainActivity
```

Tap "Track consumer_ping", wait about 15 seconds, then tap "Show diagnostics": `lastAck` shows `accepted=1`, and the event is in the Dashboard. The default endpoint is `http://10.0.2.2:8080` (the emulator's name for the host). Override it with `-Psignaldock.endpoint=...`. The app allows cleartext for `10.0.2.2`, `127.0.0.1`, and `localhost` only. For a physical device over USB, run `adb -s <device-serial> reverse tcp:8080 tcp:8080` (the serial from `adb devices`; needed when an emulator is also connected) and build with `-Psignaldock.endpoint=http://127.0.0.1:8080` (see [docs/reproduce.md](docs/reproduce.md)).

### 4. Demo app, E2E, device checks, and measurements

See [docs/reproduce.md](docs/reproduce.md). The demo app builds with `sdk/gradlew -p sdk -Psignaldock.collectionKey=... :demo:app:assembleDebug`. `scripts/e2e-delivery.sh` runs the delivery failure cases. `scripts/device-durability.sh` and `scripts/device-measure.sh` run on an emulator. `perf/` holds the server load harness.

### 5. Stop and reset

```sh
docker compose stop                     # stop; the DB volume is kept
docker compose down                     # remove containers; the DB volume is kept
docker compose down --volumes           # RESET: deletes the DB volume and ALL stored events
```

Warning: `--volumes` deletes the PostgreSQL data. You cannot undo it. Use it only when you want an empty database. Stop the emulator with `adb -s emulator-5584 emu kill`.

### Limitations

- Local only. The stack binds to `127.0.0.1`, uses example-grade secrets, and has no TLS. It is not for the public internet.
- Performance numbers are measurements of one Mac with Docker limits. They are not an SLA and not a pool-size choice ([ADR-0013](docs/adr/0013-server-load-measurement-harness.md)).
- SDK device measurement ran on emulators only: a debug build on API 24 and API 37, and an R8 release build on API 37 ([ADR-0014](docs/adr/0014-sdk-device-measurement-harness.md), [ADR-0018](docs/adr/0018-sdk-release-r8-measurement.md)). No physical device was measured.
- Cleartext HTTP works only for `10.0.2.2`, `127.0.0.1`, and `localhost` (physical device: `adb reverse`, see [docs/reproduce.md](docs/reproduce.md)). A LAN address over HTTP is not supported. The `adb reverse` route was tested on an emulator only; no physical device was used.
- The scripts accept emulators only. `scripts/device-measure.sh` needs macOS.
- Deduplication lasts while the server row exists. The design does not claim exactly-once delivery.

## Add the SDK to an app

The Maven repository ZIP and its `.sha256` file are release artifacts of the GitHub Release [`v0.1.0`](https://github.com/HyeokjaeChoi/signaldock/releases/tag/v0.1.0) ([release notes](docs/releases/v0.1.0.md), [changelog](CHANGELOG.md)). `scripts/release.sh 0.1.0` builds both into `dist/`. Verify the ZIP, then unzip it into a folder:

```sh
shasum -a 256 -c signaldock-sdk-android-0.1.0-maven.zip.sha256   # run in the folder with both files
```

Add the folder as a repository and depend on the coordinate:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("/path/to/unzipped/folder")
            content { includeGroup("local.signaldock") }
        }
    }
}
// app/build.gradle.kts
dependencies { implementation("local.signaldock:sdk-android:0.1.0") }
```

Requirements of the 0.1.0 AAR:

- `compileSdk` 36 or newer (`aarMetadata.minCompileSdk=36`, [ADR-0006](docs/adr/0006-sdk-delivery-decisions.md)), `minSdk` 24 or newer.
- Kotlin 2.2 or newer and AGP 9.x. The SDK metadata is 2.2, so AGP 9's default built-in Kotlin works with no extra Kotlin plugin ([ADR-0009](docs/adr/0009-maven-zip-and-consumer-app.md), Q132).
- The host app declares `INTERNET`. The SDK needs no R8 rules of its own. Use an `https://` endpoint unless the app allows cleartext for that host; the SDK does not switch an `http://` endpoint to HTTPS (ADR-0009).
- Call `SignalDock.initialize(context, SignalDockConfig(endpoint, collectionKey))` once (for example in `Application.onCreate`), then `track("name") { property("key", value) }` and `diagnostics()` on the returned instance.

### Supported versions

| Item | 0.1.x |
| --- | --- |
| Minimum Android API (`minSdk`) | 24 |
| `compileSdk` of the host app | 36 or newer |
| Kotlin | 2.2 or newer |
| Android Gradle Plugin | 9.x |

The SDK is before 1.0. Fixes are best effort. See [docs/versioning.md](docs/versioning.md) for breaking changes and deprecation, [SECURITY.md](SECURITY.md) to report a vulnerability, and [CONTRIBUTING.md](CONTRIBUTING.md) to contribute.

The [SDK integration guide](docs/integration-guide.md) ([한국어](docs/integration-guide.ko.md)) covers `track()` results, cancellation, offline and retry behavior, diagnostics, privacy, versions, and troubleshooting. Optional typed event functions generated from a tracking plan: see [`tracking-plan/`](tracking-plan/) and [ADR-0021](docs/adr/0021-tracking-plan-codegen.md). The server marks each stored event `planned`, `unplanned_event`, or `invalid_properties` against that plan and never rejects one ([ADR-0022](docs/adr/0022-server-tracking-plan-status.md)).

## Repository guide

The repository holds design documents and product code under `sdk/`, `server/`, and `dashboard/`.
The spec, verification plans, and implementation issues in `.scratch/signaldock-portfolio/` are the approved design sources tracked in Git. Personal conversations and résumé review records are excluded.

| Path | Contents |
| --- | --- |
| [architecture.md](architecture.md) | SDK and server boundaries; ACK, retry, contract, and distribution design |
| [docs/architecture/](docs/architecture/) | Diagrams, onepager HTML, and source text |
| [docs/adr/](docs/adr/) | Architecture decision records, one file for each decision |
| [GLOSSARY.md](GLOSSARY.md) | Collection success, receipt success, installation ID, and event ID definitions |
| [.scratch/signaldock-portfolio/spec.md](.scratch/signaldock-portfolio/spec.md) | Detailed design and completion criteria |
| [.scratch/signaldock-portfolio/final-review.md](.scratch/signaldock-portfolio/final-review.md) | Final design review |
| [.scratch/signaldock-portfolio/dashboard-verification-defaults.md](.scratch/signaldock-portfolio/dashboard-verification-defaults.md) | Dashboard query and display defaults |
| [.scratch/signaldock-portfolio/performance-plan.md](.scratch/signaldock-portfolio/performance-plan.md) | Performance test conditions, criteria, and raw data plan |
| [.scratch/signaldock-portfolio/issues/](.scratch/signaldock-portfolio/issues/) | Implementation tasks |

This project is licensed under the Apache License 2.0. See [LICENSE](LICENSE).
