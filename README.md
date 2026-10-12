# SignalDock

An Android event SDK designed to save events on the device and confirm server receipt through an ACK after commit.

This monorepo contains the design for the SDK and a local data processing server.
Review the path from an app's `track()` call through a durable queue, retries, server deduplication, and independent artifact installation.

> **Status: 2026-10-12**
> Implementation is in progress. The SDK, the server, the Android demo app, and the Dashboard are implemented. The server and Dashboard run with Docker Compose, the SDK builds into a local Maven ZIP, and an independent consumer app uses that ZIP with an R8 release build (see below).
> Delivery failure E2E (normal, ACK loss, partial rejection, content conflict) runs with `scripts/e2e-delivery.sh` (see below; results in issue 21). Offline recovery on devices, contract and DB change checks, and performance results are not yet available.

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

![Planned SignalDock structure: the demo app calls the SDK API, Room stores events, and a shared sender sends them to Ktor. The Dashboard queries server records.](docs/architecture/signaldock-architecture.svg)

## What to review

An SDK's success response tells the host app what it can do next.
SignalDock separates durable device storage from server receipt, with recovery rules for each stage.
This portfolio for the Datarize Mobile SDK Engineer role covers public API design, lifecycle management, recovery, compatibility, and independent distribution.

| Design concern | Decision | Planned evidence, not yet verified |
| --- | --- | --- |
| Offline collection within a storage limit | Collection succeeds at Room commit. At the queue limit, preserve existing events and return `QueueFull` for new calls | 1,000 offline events and process restart; concurrent inserts and capacity boundaries |
| Lost ACKs without duplicate storage | Retry with the same `eventId`. Use a server unique constraint and content comparison; ACK after commit | Block the response after commit; test duplicate and conflict results; compare unique rows and counts |
| Foreground response and background recovery | Share the sender and durable retry state with WorkManager. Keep collection writes separate from the send lock | Sender contention, cancellation, scheduling recovery, and retry waits across restart |
| Shared contracts and independent releases | OpenAPI / JSON with generated source drift checks. Install the Maven ZIP in a separate R8 consumer app | Scalar round trips, earlier fixtures, migrations, artifact installation, and R8 release execution |

## Planned event flow

1. After initialization succeeds, the host app calls `track()`. Collection succeeds after input validation and Room storage.
2. The shared sender checks the earliest retry time and sends a batch. It holds no Room transaction open during HTTP.
3. The server validates each event, handles duplicates, and returns an ACK after PostgreSQL commit.
4. The SDK validates the entire ACK before it applies queue changes atomically. An ambiguous ACK leaves the whole batch in the queue.
5. App diagnostics show the device queue and recent ACK. The Dashboard shows stored server events and their details.

A lost ACK does not change the event ID. The same ID and content produce duplicate success; different content produces a conflict rejection.
Deduplication lasts while the server row exists. The design does not claim exactly-once delivery.

## Planned stack

| Area | Choice |
| --- | --- |
| Android SDK | Kotlin · minSdk 24 · Coroutines · Room · Retrofit / OkHttp · kotlinx.serialization · WorkManager |
| Demo app | Jetpack Compose · ViewModel. The SDK has no dependency on this UI stack |
| Server | Kotlin / Ktor · PostgreSQL · jOOQ · JDBC / HikariCP · Flyway |
| Verification Dashboard | React · TypeScript · Vite · Recharts |
| Contracts and distribution | OpenAPI / JSON · generated sources tracked in Git · Docker Compose entry point · local Maven repository ZIP |

Exact dependency versions need build verification and pinning during implementation.
The first coordinate is `local.signaldock:sdk-android:0.1.0` (local only, not Maven Central). Build the ZIP as described below.

## Scope and verification

The first release covers a native Android SDK and one local project.
The demo follows product view, cart, and simulated purchase. The Dashboard helps verify SDK integration.
iOS / Flutter, automatic events, `identify`, campaign / push, funnels, multiple accounts, public internet operation, and Maven Central are outside this release.

Functional verification is pending. The [completion criteria](.scratch/signaldock-portfolio/spec.md#7-completion-criteria-and-verification-plan) require environment records, source revision/hash, and event ID comparisons.
Load and latency values in the [performance plan](.scratch/signaldock-portfolio/performance-plan.md) are targets, not measured results or an SLA.
Steps to run and stop the server and Dashboard, to build the Maven ZIP, and to run the consumer app are below.

## Run the server and Dashboard

Needs Docker with Compose. The first build downloads base images and dependencies, so it is not fully offline.

```sh
cp .env.example .env                 # change the example keys and password
docker compose up -d --build         # PostgreSQL, server, Dashboard
open http://127.0.0.1:5173           # Dashboard (SIGNALDOCK_DASHBOARD_PORT)
docker compose stop                  # stop; the DB volume is kept
docker compose down --volumes        # RESET: deletes the DB volume and all events
```

- The server listens on `127.0.0.1:8080`. An Android emulator reaches it as `http://10.0.2.2:8080`. Send events with `SIGNALDOCK_COLLECTION_KEY`.
- The Dashboard holds no key. nginx proxies `GET /v1/*` and adds `SIGNALDOCK_QUERY_KEY` ([ADR-0008](docs/adr/0008-dashboard-and-compose-delivery.md)). All ports bind to `127.0.0.1`.
- Local development: `docker compose up -d db`, then run the server from `server/` with the variables in `.env.example` and `npm run dev` in `dashboard/` (the Vite proxy adds the query key from `SIGNALDOCK_QUERY_KEY`).

## Build the Maven ZIP

Needs JDK 21 (`scripts/setup-toolchain.sh` installs it into `.tools/`) and an Android SDK with platform `android-36` (`ANDROID_HOME`).

```sh
export JAVA_HOME="$PWD/.tools/jdk-21.0.12.1+1"
sdk/gradlew -p sdk :sdk-android:signaldockMavenZip --no-daemon
# -> sdk/sdk-android/build/distributions/signaldock-sdk-android-0.1.0-maven.zip
```

The ZIP is a Maven folder repository: AAR, sources jar, POM, and Gradle module metadata for `local.signaldock:sdk-android:0.1.0`. The first build downloads dependencies, so it is not fully offline.

## Add the SDK to an app

Unzip the ZIP into a folder, then add it as a repository and depend on the coordinate:

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
- The host app declares `INTERNET`. The SDK needs no R8 rules of its own. The SDK reaches the server over HTTPS unless the app allows cleartext for that host.
- Call `SignalDock.initialize(context, SignalDockConfig(endpoint, collectionKey))` once (for example in `Application.onCreate`), then `track("name") { property("key", value) }` and `diagnostics()` on the returned instance.

## Run the consumer app

`compatibility/consumer-android/` is a separate Gradle build. It depends only on the unzipped folder repository, compiles with API 36, and builds a release APK with R8. One command builds the ZIP, unzips it into a clean temporary folder, and builds the APK:

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk"
scripts/verify-consumer.sh
```

To run it against the Compose stack on an emulator (the collection key is baked in at build time; use your `.env` value):

```sh
cp .env.example .env && docker compose up -d --build
SIGNALDOCK_REPO_DIR=/tmp/signaldock-maven scripts/verify-consumer.sh   # keep the unzipped folder
compatibility/consumer-android/gradlew -p compatibility/consumer-android \
  -Psignaldock.repo=/tmp/signaldock-maven -Psignaldock.collectionKey=<SIGNALDOCK_COLLECTION_KEY> :app:assembleRelease
adb install -r compatibility/consumer-android/app/build/outputs/apk/release/app-release.apk
adb shell am start -n local.signaldock.consumer/.MainActivity
```

Tap "Track consumer_ping", wait about 15 seconds, then tap "Show diagnostics": `lastAck` shows `accepted=1`. The app allows cleartext for `10.0.2.2` only. The default endpoint is `http://10.0.2.2:8080`; override it with `-Psignaldock.endpoint=...`. For a physical device, use a reachable HTTPS endpoint.

## Run the delivery failure checks

`scripts/e2e-delivery.sh` runs four cases against the Compose stack and the consumer app on an emulator: normal E2E, ACK loss (a fault proxy drops the response after the server commits), partial rejection, and content conflict ([ADR-0010](docs/adr/0010-delivery-failure-e2e-harness.md)). Case 1 uses both apps: the demo app (product, then add to cart: the path the spec names) and the consumer app (the R8 build of the published AAR; cases 2 and 3 use it for exact `diagnostics()` values). It builds and installs both. It needs Python 3, `.env`, the running stack, and a booted emulator. It is local only and not part of CI.

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk"
docker compose up -d --build
"$ANDROID_HOME/emulator/emulator" -avd signaldock-api37 -no-window -no-audio -no-boot-anim &
scripts/e2e-delivery.sh          # all cases; or: scripts/e2e-delivery.sh 2 3
```

It prints the environment, the event ID comparisons, and a PASS/FAIL summary. The events stay in the DB.

## Repository guide

The repository holds design documents and product code under `sdk/`, `server/`, and `dashboard/`.
The spec, verification plans, and implementation issues in `.scratch/signaldock-portfolio/` are the approved design sources tracked in Git. Personal conversations and résumé review records are excluded.

| Path | Contents |
| --- | --- |
| [architecture.md](architecture.md) | SDK and server boundaries; ACK, retry, contract, and distribution design |
| [docs/architecture/](docs/architecture/) | Diagrams, onepager HTML, and source text |
| [docs/adr/](docs/adr/) | Installation identity and OpenAPI / JSON decisions |
| [GLOSSARY.md](GLOSSARY.md) | Collection success, receipt success, installation ID, and event ID definitions |
| [.scratch/signaldock-portfolio/spec.md](.scratch/signaldock-portfolio/spec.md) | Detailed design and completion criteria |
| [.scratch/signaldock-portfolio/final-review.md](.scratch/signaldock-portfolio/final-review.md) | Final design review |
| [.scratch/signaldock-portfolio/dashboard-verification-defaults.md](.scratch/signaldock-portfolio/dashboard-verification-defaults.md) | Dashboard query and display defaults |
| [.scratch/signaldock-portfolio/performance-plan.md](.scratch/signaldock-portfolio/performance-plan.md) | Performance test conditions, criteria, and raw data plan |
| [.scratch/signaldock-portfolio/issues/](.scratch/signaldock-portfolio/issues/) | Implementation tasks |

These materials are for private technical evaluation. A public distribution license has not been selected.
