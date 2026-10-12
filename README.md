# SignalDock

An Android event SDK designed to save events on the device and confirm server receipt through an ACK after commit.

This monorepo contains the design for the SDK and a local data processing server.
Review the path from an app's `track()` call through a durable queue, retries, server deduplication, and independent artifact installation.

> **Status: 2026-10-12**
> Implementation is in progress. The SDK, the server, the Android demo app, and the Dashboard are implemented, and the server and Dashboard run with Docker Compose (see below).
> The SDK Maven ZIP, the independent consumer app, end-to-end verification, and performance results are not yet available.

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
The planned first coordinate is `local.signaldock:sdk-android:0.1.0`. No installable artifact is available yet.

## Scope and verification

The first release covers a native Android SDK and one local project.
The demo follows product view, cart, and simulated purchase. The Dashboard helps verify SDK integration.
iOS / Flutter, automatic events, `identify`, campaign / push, funnels, multiple accounts, public internet operation, and Maven Central are outside this release.

Functional verification is pending. The [completion criteria](.scratch/signaldock-portfolio/spec.md#7-completion-criteria-and-verification-plan) require environment records, source revision/hash, and event ID comparisons.
Load and latency values in the [performance plan](.scratch/signaldock-portfolio/performance-plan.md) are targets, not measured results or an SLA.
Steps to run and stop the server and Dashboard are below. Steps to integrate the SDK from the Maven ZIP will follow with that artifact.

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
