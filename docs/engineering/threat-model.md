# Threat model

Status: written 2026-10-15, refreshed 2026-10-18 UTC against `main` at SDK `v0.2.0`. Earlier refresh: 2026-10-15 UTC (issue 51) after PRs #104 to #106, #108, and #111. Later PRs that change this model: #144 to #148, #158 to #161, #168, #173, #174, #180, #181. Method: the [OWASP threat modeling process](https://owasp.org/www-community/Threat_Modeling_Process) (scope, data flow, threats, mitigations, review) with STRIDE per element. Mobile controls are grouped by the [OWASP MASVS](https://mas.owasp.org/MASVS/) categories.
Every mitigation cites a file. "Gap" means I found no mitigation in the repo. "Excluded on purpose" means the user decided to leave the feature out because the server runs only on the local machine ([ADR-0028](../adr/0028-server-production-features-excluded.md), spec Q157). The risk stays real if the server is exposed. Ratings are mine, not measured.
This is a design-level model of a local evaluation project. It is not a penetration test and no external review was done.

Short paths: `SDK tests` = `sdk/sdk-android/src/test/kotlin/local/signaldock/`; `DEV` = `sdk/sdk-android/src/androidTest/kotlin/local/signaldock/device/`; `SRV` = `server/app/src/test/kotlin/local/signaldock/server/`.

## 1. Scope and assets

In scope: the Android SDK (`sdk/sdk-android`), the ingest and query server (`server/`) with its two ingest routes (`POST /v1/events:batch` and `POST /v1/client-reports`) and its query routes (including `GET /v1/client-report-counts`), PostgreSQL, the Dashboard with its nginx proxy (`dashboard/`), and the Compose setup (`docker-compose.yml`).
Spec 1 sets the deployment: one local project, synthetic data, no login, no public internet.

| Asset | Where | Why it matters |
| --- | --- | --- |
| Queued events | SDK Room database in `noBackupFilesDir` | Not yet delivered. May hold app-defined properties |
| Installation ID | SDK file in `noBackupFilesDir` ([ADR-0001](../adr/0001-installation-scoped-identity.md)) | Pseudonymous install identifier |
| Collection key | Host app configuration, inside the app | Lets a holder write events. Public by design |
| Query key | Server and nginx environment (`SIGNALDOCK_QUERY_KEY`) | Lets a holder read all stored events |
| Stored events | PostgreSQL volume `signaldock-db` | The product data |
| Client reports | PostgreSQL table `client_reports` (migration `V3__create_client_reports.sql`) | Dropped-event counts by SDK version and reason. No installation ID and no event data (ADR-0036 items 4 and 8). Approximate |
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
- B3: device to server over HTTP. Untrusted network. The SDK rejects an `http` endpoint at init unless the host sets `allowInsecureEndpoint = true` ([ADR-0035](../adr/0035-sdk-https-enforcement.md); `internal/validation/Validation.kt`).
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
| SDK to server (B3) | I, T | Network attacker reads or changes events and the key in transit | The SDK sends the key as Bearer only to the configured endpoint and follows no redirects (`SDK tests/TransportTest.kt` "redirects are not followed"). Android blocks cleartext by default; the consumer and demo apps allow it only for local hosts ([ADR-0009](../adr/0009-maven-zip-and-consumer-app.md) item 5; spec Q134) | The SDK requires HTTPS by default ([ADR-0035](../adr/0035-sdk-https-enforcement.md)); it never upgrades `http`. A host that sets `allowInsecureEndpoint = true` and allows cleartext sends the key in the clear. The local server has no TLS. No certificate pinning. No payload signing |
| SDK to server | D | Attacker blocks the network | Queue keeps events; backoff and Worker ([failure-modes.md](failure-modes.md) rows 1 and 7) | Queue cap of 10,000 events; then `QueueFull` |
| Ingest API (B3) | S | A key holder posts as any `installationId` | Constant-time key compare (`server/app/src/main/kotlin/local/signaldock/server/RequestGuards.kt`); wrong key gives 401 `COLLECTION_KEY_INVALID` | `installationId` and `eventId` are client-chosen and not verified. A key holder can fake any installation (section 3) |
| Ingest API | T | Malformed or hostile payload | Strict contract: unknown fields rejected, size caps (256 KiB body, 16 KiB event), UTF-8 and range checks, flat scalar properties (`SRV/IngestValidationTest.kt`; body cap in `server/app/src/main/kotlin/local/signaldock/server/Application.kt`). A nested or null property value is rejected per item, not per request ([ADR-0027](../adr/0027-request-400-contract-only.md)). XSS: `dashboard/src/xss.test.tsx` renders hostile names, property keys and values, SDK versions, and filter input through the real `App` ("renders event name, property key and value, and top-event KPI as text only", "keeps a hostile name typed into the filter as input text") and an unknown plan status through `PlanBadge` ("never renders a server-sent unknown status as markup"). `scripts/check-repo-rules.sh` fails on `dangerouslySetInnerHTML`, `innerHTML`, `outerHTML`, or `insertAdjacentHTML` in `dashboard/src`, `perf/report/src`, and `web-shared` (test files exempt) | Properties are free values from a key holder, and React escapes them. Gap: `perf/report` has no test runner, so only the static check covers it (its data comes from the repo's own perf run, not from key holders). Gap: the tests use hostile text in the DOM test environment, not a real browser |
| Ingest API (client reports) | S, T | A key holder posts fake or repeated drop counts to `POST /v1/client-reports` | Same collection key and contract header as ingest; strict schema: 1 to 5 entries, one per reason, count 1 to 1,000,000, body at most 4 KiB ([ADR-0036](../adr/0036-sdk-client-reports.md) items 3 and 8; `SRV/ClientReportTest.kt`) | Counts are approximate and can be faked. A report has no ID and no dedup, so a lost response can count twice (ADR-0036 item 7). The Dashboard says the counts are approximate. A report holds no installation or event data |
| Ingest API | T | Same `eventId` with different content overwrites data | Unique constraint; different content gives `conflict` and keeps the first row (spec Q61; `SRV/IngestionIntegrationTest.kt`) | None found |
| Ingest API | R | A sender denies sending events | The server stores `receivedAt` and the event (`received_at` column in `server/persistence/src/main/resources/db/migration/V1__create_events.sql`; spec 5) | Excluded on purpose (ADR-0028, Q157): no access log with caller identity, no audit trail. Keys are shared, so a sender cannot be named. Risk: no record of who called the server |
| Ingest API | I | Error text reveals keys, SQL, or the key role | Errors name codes only. Startup errors name variables, not values (spec Q131; `SRV/KeyConfigTest.kt`). The DB observer logs stage names and times, not SQL (`server/persistence/src/main/kotlin/local/signaldock/persistence/Database.kt`) | None found |
| Ingest API | D | Request flood or slow clients | Four slots, bounded admission wait, 503 with `Retry-After`, request time budget ([ADR-0004](../adr/0004-server-runtime-and-receipt-boundaries.md); [overload-1 report](../../perf/results/overload-1/report.md)). Host port is bound to `127.0.0.1` | Excluded on purpose (ADR-0028, Q157): no per-key or per-IP rate limit, no `429`, and no connection limit. 503 is global, so one noisy client can shed the others |
| Ingest API | E | Collection key used to read data | Key roles are separate. Query endpoints reject the collection key with 401 `QUERY_KEY_INVALID`; ingest rejects the query key (`SRV/QueryRoutesTest.kt` "every endpoint requires the query key and rejects the collection key"; `SRV/IngestionIntegrationTest.kt` "route rejects the query key with the collection key error"; [ADR-0007](../adr/0007-dashboard-query-api.md)) | None found |
| Key guessing | S | An attacker guesses a key | The operator sets keys; the server needs them non-blank and different (spec Q131) | Excluded on purpose (ADR-0028; Q131 sets no length rule, Q128 has no key table): no minimum length, no lockout, no rate limit on failed keys. Risk: a weak key can be guessed without limit. `.env.example` holds sample keys that must be changed |
| PostgreSQL (B4) | T, I | Direct DB access | Password required; image pinned by digest; DB port bound to `127.0.0.1` (`docker-compose.yml`); server runs as a non-root user (`server/Dockerfile`) | The DB password is in `.env` in plain text. No TLS to the DB. The DB port is published to the host. No disk encryption in the repo |
| PostgreSQL | T | SQL injection | jOOQ builds typed queries; filters are exact values; request parameters are validated strictly ([ADR-0007](../adr/0007-dashboard-query-api.md); `server/query/src/test/kotlin/local/signaldock/query/QueryTest.kt`). Tests (PR #111): `SRV/IngestionIntegrationTest.kt` ("sql injection text in name, property key, and value is stored verbatim and tables stay intact"); `SRV/QueryRoutesTest.kt` ("sql injection text in every query parameter matches exactly or is rejected, and tables stay intact"). Gap: the last test run is recorded in the PR #111 description only; no committed result file | Gap: a fixed set of injection strings, not fuzzing. |
| PostgreSQL | D | Disk fills | Excluded on purpose (ADR-0028; spec 5): no TTL, no retention job, no delete function. About 1 KB per event ([design-review.md](design-review.md)). The `client_reports` table also has no retention (ADR-0036 item 8). It gets at most 5 rows per report, so a key holder can grow it without limit too | There is no application delete function (ADR-0028). Events stay until an explicit deletion or a database reset (spec 5). The repo documents one reset command, `docker compose down --volumes` (the RESET line in the `docker-compose.yml` header). The database grows without limit until then |
| Query API and nginx (B5, B6) | S, E | A browser user reads all data | The query key is added by nginx and is never in the bundle (`dashboard/nginx.conf.template`; `npm run check-bundle-key`, ADR-0008). Port bound to `127.0.0.1`. The server has no CORS | Excluded on purpose (ADR-0028; spec 1, Q128): no login. Anyone who reaches the Dashboard port reads everything. Do not expose it |
| Query API and nginx | T | A browser sends writes with the query key | `limit_except GET { deny all; }`; nginx overwrites any client `Authorization` header (ADR-0008) | None found |
| Query API | I | A wrong role learns that a value is a valid collection key | Always 401 `QUERY_KEY_INVALID`, never 403 (spec Q130) | None found |
| Query API | D | Heavy aggregate queries | Max 30 dates, 100 rows per page, 720 buckets, statement timeout, shared bounded slots ([ADR-0007](../adr/0007-dashboard-query-api.md)). `GET /v1/client-report-counts` takes the query key, the same period rule (30 dates), and returns at most 100 rows (ADR-0036 item 9) | Heavy queries can shed ingest ([failure-modes.md](failure-modes.md) row 17) |
| Query key in nginx | I | Key read from the container environment | Not in the image or build args (ADR-0008 "nginx") | Anyone with Docker access on the host can read it |
| Tracking plan file | T | A changed plan marks events wrongly | Mounted read-only; read once at start; an invalid file stops the server (`SRV/TrackingPlanFileTest.kt`) | Marks are not authenticated data. Whoever edits the file changes the marks |
| Dependencies and build | T | Supply chain | Images pinned by digest; tool versions pinned ([TOOLCHAIN.md](../../TOOLCHAIN.md)); generated sources checked for drift (`scripts/verify-contract.sh`). Renovate proposes weekly updates for Gradle (including the shared version catalog), npm, GitHub Actions, Docker, and Compose (`.github/renovate.json5`, [ADR-0031](../adr/0031-renovate-dependency-updates.md)). Release workflow: actions pinned by commit SHA; the build job has read permission only and the publish job runs no project code (ADR-0024 items 3 and 4). The publish job signs a build provenance attestation for the release ZIP, so a consumer can run `gh attestation verify <zip> --repo HyeokjaeChoi/signaldock` ([ADR-0039](../adr/0039-release-provenance-attestation.md)). Gap: the attestation is not produced until the next release tag; `v0.2.0` and earlier have none, and the verify command has not run on a real release. The SDK release runtime and compile classpaths are committed in `sdk/sdk-android/dependencies/`, and `scripts/verify-contract.sh` fails when they change without the baseline ([ADR-0033](../adr/0033-sdk-dependency-baseline.md)). `SECURITY.md` gives a private reporting path. The `gradle-wrapper` job in `.github/workflows/repo-rules.yml` checks every `gradle-wrapper.jar` against Gradle's published checksums (issue 55). Each of the four Gradle builds checks every dependency and plugin file against SHA-256 checksums in `gradle/verification-metadata.xml`, and a changed or unlisted file fails the build ([ADR-0038](../adr/0038-gradle-dependency-verification.md)) | Gap: the checksums are trust on first use (no PGP signatures), so a file that was already bad when the metadata was written is not caught (ADR-0038 item 1). Gap: Renovate does not update the metadata for this repo, so the author runs `scripts/update-gradle-verification.sh` on each Renovate Gradle PR (ADR-0038 item 6). Gap: Robolectric downloads its `android-all` jars at test run time outside Gradle, so the verification metadata does not cover them. GitHub Dependabot alerts cover npm ([ADR-0031](../adr/0031-renovate-dependency-updates.md) item 9; [GitHub docs](https://docs.github.com/en/code-security/concepts/supply-chain-security/dependabot-alerts)). Gap: GitHub Actions are pinned by commit SHA, and GitHub raises Dependabot alerts for Actions only for semantic-version references ([GitHub docs](https://docs.github.com/en/code-security/concepts/supply-chain-security/dependabot-alerts)), so a vulnerable pinned action raises no alert; Renovate still proposes the new SHA. For Gradle, Renovate checks OSV for the declared dependencies ([ADR-0031](../adr/0031-renovate-dependency-updates.md) item 9). Gap: a vulnerable transitive Gradle dependency raises no alert (no dependency submission, Q161 amended). Gap: no SBOM; none of the workflows (`.github/workflows/repo-rules.yml`, `.github/workflows/pr-description.yml`, `.github/workflows/release.yml`, `.github/workflows/codeql.yml`) writes one. Renovate updates dependencies but does not scan the code that ships. Static analysis: CodeQL workflow `.github/workflows/codeql.yml` ([ADR-0032](../adr/0032-codeql-advanced-setup.md)) covers Kotlin, Java, TypeScript, Python, and the workflow files. The first run on `main` (run 38936500321, 2026-10-16 UTC) uploaded results for all four languages, including `java-kotlin` (issue 54, ADR-0032 "Gaps"). The 51 alerts were triaged on 2026-10-16 UTC: 0 security alerts, 14 fixed, 23 generated TypeScript alerts removed by `paths-ignore`, 14 dismissed with reasons ([ADR-0032](../adr/0032-codeql-advanced-setup.md) "Triage result"). The Renovate app was installed on 2026-10-16 UTC, limited to this repository, and opened PR #149 and the Dependency Dashboard #150 (issue 52, ADR-0031 "Gaps"). Google Maven lookups work: Dependency Dashboard #150 (2026-10-16 UTC) lists updates found for `androidx.activity:activity-compose` (1.13.0), `androidx.lifecycle` (2.11.0), and the AGP plugins `com.android.library` and `com.android.application` (9.4.1). The release workflow ran for `v0.2.0`: run 39002106137 succeeded on 2026-10-17 UTC and published [the release](https://github.com/HyeokjaeChoi/signaldock/releases/tag/v0.2.0) |

## 5. MASVS view of the SDK

| MASVS category | Status | Evidence or gap |
| --- | --- | --- |
| STORAGE | Partial | App-private, backup-excluded storage. No encryption of the queue. The host must keep sensitive data out of properties; the SDK has no check for it |
| CRYPTO | Not used | The SDK has no crypto of its own. `UUID.randomUUID()` (ADR-0001) gives identifiers, not secrets |
| AUTH | Partial | A shared write-only key, not user authentication (spec 5) |
| NETWORK | Partial | No redirects, strict ACK validation, timeouts. HTTPS is enforced at init unless `allowInsecureEndpoint = true` (ADR-0035); no pinning |
| PLATFORM | Partial | The SDK declares no permissions; the host declares `INTERNET` (ADR-0011 item 1). I did not review exported components |
| CODE | Partial | Public API check ([ADR-0019](../adr/0019-sdk-public-api-check.md)); generated DTOs are `internal`; R8 release consumer builds ([ADR-0009](../adr/0009-maven-zip-and-consumer-app.md)); Renovate with vulnerability fix PRs from GitHub Dependabot alerts (npm; not SHA-pinned Actions, a Gap) and OSV (declared Gradle dependencies) ([ADR-0031](../adr/0031-renovate-dependency-updates.md) item 9). Gap: a vulnerable transitive Gradle dependency raises no alert. CodeQL workflow ([ADR-0032](../adr/0032-codeql-advanced-setup.md); first `main` run 38936500321 uploaded results; triaged, 0 security alerts, ADR-0032 "Triage result") |
| RESILIENCE | Not a goal | No tamper or root detection. The SDK does not defend against a hostile host or device |
| PRIVACY | Partial | See section 6 |

## 6. Privacy

What the repo shows:
- Data minimization: no `identify`, no automatic events, one pseudonymous installation ID, and the host chooses event names and properties (spec 1, 3; [integration guide](../integration-guide.md) section 8). An event holds `eventId`, `installationId`, `name`, `occurredAt`, `sdkVersion`, and properties (spec 3 "Common information").
- No SDK content in logs (spec Q115).
- A new ID starts after clear, reinstall, or restore (ADR-0001).
- Rejection diagnostics keep no payload and no name (ADR-0003 item 7).

Gaps:
- Opt-out exists since ADR-0034 (spec Q156): `setOptOut`, `isOptedOut`, and `clearQueue` (`sdk/sdk-android/api/sdk-android.api`; `OptOutTest.kt`). Remaining gaps: queued events are still sent after opt-out unless the host calls `clearQueue()`, there is no consent text, and the server has no delete (ADR-0028). The case `optout` of `scripts/device-durability.sh` covers a real process restart with the marker (ADR-0034, amendment). It passed on an API 37 emulator on 2026-10-17. As a comparison, Amplitude's documentation says: "Amplitude doesn't save or send events while `optOut` is `true`" ([Amplitude Android Kotlin SDK](https://amplitude.com/docs/sdks/analytics/android/android-kotlin-sdk)). Sources for other vendors are in the ADR-0034 amendment "vendor sources".
- No retention. The server keeps events until explicit deletion or DB reset (spec 5). Excluded on purpose for local-only operation (ADR-0028).
- No per-installation delete or export on the server. The delete function is excluded on purpose (ADR-0028); export is not covered by it.
- No data processing notice or consent text in the repo.

## 7. Residual risks

1. Public collection key: fake events are possible (accepted, section 3).
2. A host that sets `allowInsecureEndpoint = true` and allows cleartext: key and events exposed on the network (ADR-0035).
3. Unencrypted queue on a rooted device.
4. No rate limit: one client can cause 503 for all (excluded on purpose, ADR-0028). The operator can block an event name or an SDK version, or shed all ingest, with environment variables and a server restart (ADR-0037). This does not stop collection on the device, and it has no per-key or per-IP rule. Gap: no hot reload, no audit trail.
5. Dashboard without login: safe only because it is local (excluded on purpose, ADR-0028).
6. No server retention or delete (excluded on purpose, ADR-0028). Opt-out is on the device only (ADR-0034).
7. Secrets in `.env` and the container environment.
8. Dependency vulnerability coverage is partial: GitHub Dependabot alerts cover npm (not the SHA-pinned GitHub Actions), and Renovate checks OSV for the declared Gradle dependencies (ADR-0031 item 9). Transitive Gradle dependencies get no alert, and there is no SBOM. CodeQL checks first-party code only, and its first results on `main` (run 38936500321) were triaged: 0 security alerts (ADR-0032 "Triage result").
9. No access log, no key policy, no lockout (excluded on purpose, ADR-0028).
10. Covered by a test since the first version: XSS and SQL injection now have tests (PR #111; the run is recorded only in the PR description, a Gap). They cover fixed strings only.

Before any non-local use, build every feature in the ADR-0028 table first.

## 8. Out of scope

- Public internet operation, multi-tenant use, and account management (spec 1; [ADR-0028](../adr/0028-server-production-features-excluded.md) lists what the server leaves out for that reason).
- Physical devices. The JUnit device tests ran locally on API 24 and API 37.2 (ADR-0011 limits) and run in CI on API 34 x86_64 ([ADR-0023](../adr/0023-ci-device-tests-and-size-budget.md)). The fault cases ran on API 37.2 only, and CI does not run them ([ADR-0029](../adr/0029-device-fault-tests-and-power-loss.md) limits).
- A hostile host app, root detection, and reverse engineering of the AAR.
- Legal review of privacy law and data processing agreements.
- Penetration testing, fuzzing, and a formal security review.
- iOS, Flutter, and bridges (Q150).
