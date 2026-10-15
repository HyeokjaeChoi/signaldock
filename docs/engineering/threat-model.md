# Threat model

Status: written 2026-10-15. Method: the [OWASP threat modeling process](https://owasp.org/www-community/Threat_Modeling_Process) (scope, data flow, threats, mitigations, review) with STRIDE per element. Mobile controls are grouped by the [OWASP MASVS](https://mas.owasp.org/MASVS/) categories.
Every mitigation cites a file. "Gap" means I found no mitigation in the repo. Ratings are mine, not measured.
This is a design-level model of a local evaluation project. It is not a penetration test and no external review was done.

Short paths: `SDK tests` = `sdk/sdk-android/src/test/kotlin/local/signaldock/`; `DEV` = `sdk/sdk-android/src/androidTest/kotlin/local/signaldock/device/`; `SRV` = `server/app/src/test/kotlin/local/signaldock/server/`.

## 1. Scope and assets

In scope: the Android SDK (`sdk/sdk-android`), the ingest and query server (`server/`), PostgreSQL, the Dashboard with its nginx proxy (`dashboard/`), and the Compose setup (`docker-compose.yml`).
Spec 1 sets the deployment: one local project, synthetic data, no login, no public internet.

| Asset | Where | Why it matters |
| --- | --- | --- |
| Queued events | SDK Room database in `noBackupFilesDir` | Not yet delivered. May hold app-defined properties |
| Installation ID | SDK file in `noBackupFilesDir` ([ADR-0001](../adr/0001-installation-scoped-identity.md)) | Pseudonymous install identifier |
| Collection key | Host app configuration, inside the app | Lets a holder write events. Public by design |
| Query key | Server and nginx environment (`SIGNALDOCK_QUERY_KEY`) | Lets a holder read all stored events |
| Stored events | PostgreSQL volume `signaldock-db` | The product data |
| DB password | `.env` | Full DB access |
| Tracking plan file | `tracking-plan/plan.json`, mounted read-only | Changes event marks, not storage |

Out of scope: see section 8.

## 2. Data flow and trust boundaries

```
Host app --(Kotlin call)--> SDK --(HTTP, Bearer collection key)--> Ingest (Ktor) --(JDBC)--> PostgreSQL
                                                                                               ^
Browser --(HTTP, no key)--> nginx (adds query key, GET only) --(HTTP)--> Query (Ktor) --(JDBC)-+
```

Trust boundaries:
- B1: host app code to SDK. The host is trusted code in the same process. A malicious host can send anything.
- B2: SDK storage on the device. Safe from other apps. Not safe on a rooted device.
- B3: device to server over HTTP. Untrusted network. The SDK accepts `http` and `https` (`sdk/sdk-android/src/main/kotlin/local/signaldock/Results.kt`).
- B4: server to PostgreSQL. Inside the Compose network. The DB port is also published on `127.0.0.1` (`docker-compose.yml`).
- B5: browser to nginx. Local only: port bound to `127.0.0.1` ([ADR-0008](../adr/0008-dashboard-and-compose-delivery.md)). No login.
- B6: nginx to server. Inside the Compose network. nginx holds the query key.

## 3. The collection key is public by design

An app ships the key, so anyone can extract it. Spec Q128 says so: the collection key can be extracted from apps, so it is write-only, and a separate query key guards reads. Spec 5 and the [integration guide](../integration-guide.md) section 8 repeat it: the key checks access for local evaluation and is not user authentication.
Consequence: a holder of the key can write fake events. This is an accepted risk. The tracking plan (Q155) marks unexpected events but does not reject them (ADR-0022 item 1), so it is a signal and not a defense. There is no key rotation and no key table (Q128).

## 4. STRIDE per element

S spoofing, T tampering, R repudiation, I information disclosure, D denial of service, E elevation of privilege.

| Element | STRIDE | Threat | Existing mitigation (evidence) | Gap or residual risk |
| --- | --- | --- | --- | --- |
| SDK and queue (B1, B2) | T | Another app or a user edits the queue | App-private `noBackupFilesDir`; `SDK tests/StorageTest.kt` ("installation id, queue database and sidecars live only under noBackupFilesDir") | Gap: no encryption or integrity check of the SQLite file. A rooted device can read and edit it |
| SDK and queue | I | Backup or restore copies the queue elsewhere | Queue, ID, and sidecars excluded from backup; restore device test (`DEV/DurabilityPhases.kt` `install_check`, [ADR-0011](../adr/0011-sdk-device-test-harness.md) item 5) | Local transport only. Other transfer tools are not covered ([ADR-0001](../adr/0001-installation-scoped-identity.md)) |
| SDK logging | I | Logs leak event content, names, IDs, keys, or bodies | Fixed-text log messages at every level; `Config.toString` hides the key (spec Q115; `SDK tests/InitContractTest.kt`; `SDK tests/LifecycleTest.kt` "config toString hides the collection key") | Logcat is readable by a debugging user. No content is in it, by design |
| SDK to server (B3) | I, T | Network attacker reads or changes events and the key in transit | The SDK sends the key as Bearer only to the configured endpoint and follows no redirects (`SDK tests/TransportTest.kt` "redirects are not followed"). Android blocks cleartext by default; the consumer and demo apps allow it only for local hosts ([ADR-0009](../adr/0009-maven-zip-and-consumer-app.md) item 5; spec Q134) | The SDK does not require HTTPS: it accepts `http` and never upgrades. A host that allows cleartext sends the key in the clear. The local server has no TLS. No certificate pinning. No payload signing |
| SDK to server | D | Attacker blocks the network | Queue keeps events; backoff and Worker ([failure-modes.md](failure-modes.md) rows 1 and 7) | Queue cap of 10,000 events; then `QueueFull` |
| Ingest API (B3) | S | A key holder posts as any `installationId` | Constant-time key compare (`server/app/src/main/kotlin/local/signaldock/server/RequestGuards.kt`); wrong key gives 401 `COLLECTION_KEY_INVALID` | `installationId` and `eventId` are client-chosen and not verified. A key holder can fake any installation (section 3) |
| Ingest API | T | Malformed or hostile payload | Strict contract: unknown fields rejected, size caps (256 KiB body, 16 KiB event), UTF-8 and range checks, JSON nesting limit, flat scalar properties (`SRV/IngestValidationTest.kt`; body cap in `server/app/src/main/kotlin/local/signaldock/server/Application.kt`) | Properties are free values from a key holder. I found no `innerHTML` or `dangerouslySetInnerHTML` in `dashboard/src`, `perf/report/src`, or `web-shared/`, so React escapes them. Gap: no XSS test exists |
| Ingest API | T | Same `eventId` with different content overwrites data | Unique constraint; different content gives `conflict` and keeps the first row (spec Q61; `SRV/IngestionIntegrationTest.kt`) | None found |
| Ingest API | R | A sender denies sending events | The server stores `receivedAt` and the event | Gap: no access log with caller identity, no audit trail. Keys are shared, so a sender cannot be named |
| Ingest API | I | Error text reveals keys, SQL, or the key role | Errors name codes only. Startup errors name variables, not values (spec Q131; `SRV/KeyConfigTest.kt`). The DB observer logs stage names and times, not SQL (`server/persistence/src/main/kotlin/local/signaldock/persistence/Database.kt`) | None found |
| Ingest API | D | Request flood or slow clients | Four slots, bounded admission wait, 503 with `Retry-After`, request time budget ([ADR-0004](../adr/0004-server-runtime-and-receipt-boundaries.md); [overload-1 report](../../perf/results/overload-1/report.md)). Host port is bound to `127.0.0.1` | Gap: no per-key or per-IP rate limit and no connection limit. 503 is global, so one noisy client can shed the others |
| Ingest API | E | Collection key used to read data | Key roles are separate. Query endpoints reject the collection key with 401 `QUERY_KEY_INVALID`; ingest rejects the query key (`SRV/QueryRoutesTest.kt` "every endpoint requires the query key and rejects the collection key"; `SRV/IngestionIntegrationTest.kt` "route rejects the query key with the collection key error"; [ADR-0007](../adr/0007-dashboard-query-api.md)) | None found |
| Key guessing | S | An attacker guesses a key | The operator sets keys; the server needs them non-blank and different (spec Q131) | Gap: no minimum length (Q131 sets none), no lockout, no rate limit on failed keys. `.env.example` holds sample keys that must be changed |
| PostgreSQL (B4) | T, I | Direct DB access | Password required; image pinned by digest; DB port bound to `127.0.0.1` (`docker-compose.yml`); server runs as a non-root user (`server/Dockerfile`) | The DB password is in `.env` in plain text. No TLS to the DB. The DB port is published to the host. No disk encryption in the repo |
| PostgreSQL | T | SQL injection | jOOQ builds typed queries; filters are exact values; request parameters are validated strictly ([ADR-0007](../adr/0007-dashboard-query-api.md); `server/query/src/test/kotlin/local/signaldock/query/QueryTest.kt`) | Gap: design claim only. No injection test was run |
| PostgreSQL | D | Disk fills | Gap: no TTL (spec 5); about 1 KB per event ([design-review.md](design-review.md)) | The only delete path is `docker compose down --volumes` |
| Query API and nginx (B5, B6) | S, E | A browser user reads all data | The query key is added by nginx and is never in the bundle (`dashboard/nginx.conf.template`; `npm run check-bundle-key`, ADR-0008). Port bound to `127.0.0.1`. The server has no CORS | Gap: no login. Anyone who reaches the Dashboard port reads everything. This is the spec 1 scope; do not expose it |
| Query API and nginx | T | A browser sends writes with the query key | `limit_except GET { deny all; }`; nginx overwrites any client `Authorization` header (ADR-0008) | None found |
| Query API | I | A wrong role learns that a value is a valid collection key | Always 401 `QUERY_KEY_INVALID`, never 403 (spec Q130) | None found |
| Query API | D | Heavy aggregate queries | Max 30 dates, 100 rows per page, 720 buckets, statement timeout, shared bounded slots ([ADR-0007](../adr/0007-dashboard-query-api.md)) | Heavy queries can shed ingest ([failure-modes.md](failure-modes.md) row 17) |
| Query key in nginx | I | Key read from the container environment | Not in the image or build args (ADR-0008 "nginx") | Anyone with Docker access on the host can read it |
| Tracking plan file | T | A changed plan marks events wrongly | Mounted read-only; read once at start; an invalid file stops the server (`SRV/TrackingPlanFileTest.kt`) | Marks are not authenticated data. Whoever edits the file changes the marks |
| Dependencies and build | T | Supply chain | Images pinned by digest; tool versions pinned ([TOOLCHAIN.md](../../TOOLCHAIN.md)); generated sources checked for drift (`scripts/verify-contract.sh`) | Gap: I found no dependency vulnerability scan or SBOM in the repo |

## 5. MASVS view of the SDK

| MASVS category | Status | Evidence or gap |
| --- | --- | --- |
| STORAGE | Partial | App-private, backup-excluded storage. No encryption of the queue. The host must keep sensitive data out of properties; the SDK has no check for it |
| CRYPTO | Not used | The SDK has no crypto of its own. `UUID.randomUUID()` (ADR-0001) gives identifiers, not secrets |
| AUTH | Partial | A shared write-only key, not user authentication (spec 5) |
| NETWORK | Partial | No redirects, strict ACK validation, timeouts. HTTPS is not enforced; no pinning |
| PLATFORM | Partial | The SDK declares no permissions; the host declares `INTERNET` (ADR-0011 item 1). I did not review exported components |
| CODE | Partial | Public API check ([ADR-0019](../adr/0019-sdk-public-api-check.md)); generated DTOs are `internal`; R8 release consumer builds ([ADR-0009](../adr/0009-maven-zip-and-consumer-app.md)). No dependency scan |
| RESILIENCE | Not a goal | No tamper or root detection. The SDK does not defend against a hostile host or device |
| PRIVACY | Partial | See section 6 |

## 6. Privacy

What the repo shows:
- Data minimization: no `identify`, no automatic events, one pseudonymous installation ID, and the host chooses event names and properties (spec 1, 3; [integration guide](../integration-guide.md) section 8). An event holds `eventId`, `installationId`, `name`, `occurredAt`, `sdkVersion`, and properties (spec 3 "Common information").
- No SDK content in logs (spec Q115).
- A new ID starts after clear, reinstall, or restore (ADR-0001).
- Rejection diagnostics keep no payload and no name (ADR-0003 item 7).

Gaps:
- No opt-out or consent API. The public API has no such call (`sdk/sdk-android/api/sdk-android.api`; integration guide section 11). The host can avoid calling `track()`, but the SDK has no stop switch, no queue clear, and no data delete for the host. As a comparison, Amplitude's documentation says: "Amplitude doesn't save or send events while `optOut` is `true`" ([Amplitude Android Kotlin SDK](https://amplitude.com/docs/sdks/analytics/android/android-kotlin-sdk)). I checked no other vendor.
- No retention. The server keeps events until explicit deletion or DB reset (spec 5).
- No per-installation delete or export on the server.
- No data processing notice or consent text in the repo.

## 7. Residual risks

1. Public collection key: fake events are possible (accepted, section 3).
2. Cleartext allowed by a host app: key and events exposed on the network.
3. Unencrypted queue on a rooted device.
4. No rate limit: one client can cause 503 for all.
5. Dashboard without login: safe only because it is local.
6. No opt-out, retention, or delete.
7. Secrets in `.env` and the container environment.
8. No dependency scan.

## 8. Out of scope

- Public internet operation, multi-tenant use, and account management (spec 1).
- Physical devices; the tests used two emulator images (ADR-0011 limits).
- A hostile host app, root detection, and reverse engineering of the AAR.
- Legal review of privacy law and data processing agreements.
- Penetration testing, fuzzing, and a formal security review.
- iOS, Flutter, and bridges (Q150).
