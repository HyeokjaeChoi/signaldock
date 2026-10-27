# iOS SDK and bridges: impact of current contract and Android SDK

Read-only research. Paths are relative to the worktree root. `server/app/...` and `server/ingestion/...` abbreviate `server/<module>/src/main/kotlin/local/signaldock/<pkg>/...`. "Est." = estimate, not in a source. "Inference" = my reasoning, not stated in a source.

## Baseline facts

- Scope excludes iOS, Flutter, RN, Unity, and WebView bridges: `.scratch/signaldock/spec.md:17`, `README.md:85`, `docs/integration-guide.md:438-439`.
- Q150: WebView bridge deferred, needs its own spec change and ADR (public API, JS interface, page allow-list): `.scratch/signaldock/spec.md:402`.
- Only Android SDK exists: `sdk/sdk-android/src/main/kotlin/local/signaldock/`, 2,223 lines Kotlin; `internal/` 1,686 lines (measured by wc).
- SDK version constant: `sdk/sdk-android/src/main/kotlin/local/signaldock/internal/transport/Versions.kt:3-4`.

## A. Native iOS SDK

Reuse as-is (wire and rules, language-neutral):
- Contract v1 JSON, headers, status codes: `openapi/contract-v1.yaml:21-31` (`SignalDock-Contract-Version`), `:42-43` (JSON only).
- Event rules: name whitespace set, RFC 3339 ms UTC, flat scalar properties: `openapi/contract-v1.yaml:526-574`, `:608` (PropertyValue).
- Installation identity model: UUIDv4, one per install, stored in backup-excluded dir, kept across restarts, new after reinstall: `docs/adr/0001-installation-scoped-identity.md:9,16`.
- Queue/ACK/retry semantics (same eventId on resend, duplicate = success, 503 keeps IDs): `openapi/contract-v1.yaml:92-93`, `:534`, `:594-597`, `docs/adr/0006-sdk-delivery-decisions.md:12-27`.
- Opt-out, HTTPS-only endpoint, diagnostics, client reports: `docs/adr/0034-sdk-opt-out.md:17-22`, `docs/adr/0035-sdk-https-enforcement.md:17`, `docs/adr/0036-sdk-client-reports.md:11-14`.

New work:
- Swift port of `internal/` (validation, queue, sender, ACK validator, retry, diagnostics). Est. 1,500-2,000 Swift lines.
- Storage: Room (`internal/SignalDockDatabase.kt`, `internal/storage/Storage.kt`) becomes SQLite or GRDB. Path rule `noBackupFilesDir/signaldock` (`internal/init/InitCoordinator.kt:132,136`) becomes an Application Support subdir with `isExcludedFromBackup` (inference).
- Scheduling: WorkManager (`internal/scheduling/WorkScheduler.kt:50-70`) and Activity-stop trigger (`internal/scheduling/DeliveryCoordinator.kt:138-148`) need iOS equivalents (BGTaskScheduler, scene-phase). iOS background limits make delivery timing weaker (inference).
- HTTP: OkHttp/Retrofit (`internal/delivery/BatchTransport.kt:78-85`) becomes URLSession.
- Logging: `android.util.Log` (`internal/init/Infra.kt:3,18-20`) becomes os.Logger.
- Public API: `initialize(context, config)` (`SignalDock.kt:154`) takes an Android Context; iOS needs its own init. Keep the same shape: `track(name) { property }`, `diagnostics()`, `setOptOut`, `clearQueue`.
- Tracking plan: new Swift output (see C.5).
- Contract codegen: `scripts/codegen.sh:50` (kotlin) and `:59` (typescript-fetch) only; a Swift generator must be added and checked against the Kotlin oneOf template override (`scripts/codegen.sh:41`, `openapi/templates`).
- Est. total: 3-5 person-weeks for a first release with tests.

Risks:
- No platform field on the wire. `sdkVersion` is the only SDK identity: `openapi/contract-v1.yaml:567-569`, `server/ingestion/.../IngestRules.kt:74`. An iOS SDK at `0.3.0` looks like Android `0.3.0`. Also `CollectionControl` blocks by exact `sdkVersion` (`server/app/.../CollectionControl.kt:11,23`), so one platform can be blocked for both.
- Installation ID must be UUIDv4 (`server/ingestion/.../IngestRules.kt:61,143`; SDK check `internal/storage/InstallationIdStore.kt:35`). iOS identifiers (IDFV, Keychain) differ. Keychain likely survives reinstall (inference), which conflicts with ADR-0001 reinstall rule (`docs/adr/0001-installation-scoped-identity.md:9`). Needs a decision.
- Timestamp precision: server rejects sub-ms timestamps (`server/ingestion/.../IngestRules.kt:137`). Swift ISO8601 formatter must emit exactly 3 fraction digits.
- Release: a `v*.*.*` tag triggers the Android-only release job (`.github/workflows/release.yml:8`, `docs/adr/0024-release-workflow-and-dependency-updates.md:13-15`). A shared tag scheme collides.
- Release gates are Android-only: `scripts/verify-contract.sh:62-72` (apiCheck, dependencyGuard, needs ANDROID_HOME); `scripts/check-repo-rules.sh:210-214` (SDK_VERSION vs Gradle). iOS needs its own gate, or it ships unchecked.
- Spec excludes iOS from first scope: `.scratch/signaldock/spec.md:17`. Building it is a scope change.

## B. Bridges (Flutter, React Native, Unity, WebView)

Common to all bridges:
- Bridges must not mint eventId or installationId; SDK owns both (`docs/adr/0001-installation-scoped-identity.md:14`; custom eventId is out of scope, `.scratch/signaldock/spec.md:82`).
- Property types: SDK overloads are String, Boolean, Int, Long, Double only (`docs/adr/0003-sdk-storage-decisions.md:15`; `sdk/sdk-android/src/main/kotlin/local/signaldock/EventBuilder.kt:27-74`). JS and Dart numbers map to Double; large integers lose precision (inference).
- Suspend API: `track`, `initialize`, `diagnostics` are `suspend` (`SignalDock.kt:54,108,154`). Non-Kotlin callers need a callback or Promise facade. This is new Kotlin code in every bridge.
- Results: sealed `TrackResult` and `InitResult` (`Results.kt:61,98-129`) must map to bridge-side types.
- Host constraints: compileSdk 36, minSdk 24 (`gradle/libs.versions.toml:30-31`, `docs/integration-guide.md:59`). Hosts on older Android setups fail to build.
- Not on Maven Central. Local Maven folder and ZIP only (`sdk/sdk-android/build.gradle.kts:67`; spec `.scratch/signaldock/spec.md:17`). A bridge package cannot pull the SDK from a public repo.

### B1. Flutter
- Can wrap the Android SDK as an AAR through a Kotlin plugin module. Reuse: whole SDK, unchanged.
- New: MethodChannel or Pigeon layer, Dart API, Kotlin facade, example app, pub.dev packaging (est.).
- Risk: Flutter on iOS is blocked until the iOS SDK (A) exists. Android-only plugin is a partial platform.

### B2. React Native
- Same as Flutter: Kotlin TurboModule or bridge wraps the AAR. Reuse: whole SDK.
- New: TS API, Kotlin module, autolinking, New Architecture support (est.).
- Risk: same iOS gap. Also the SDK's Activity lifecycle hook (`DeliveryCoordinator.kt:142`) must still see the host Activity under RN (inference).

### B3. Unity
- Reuse: AAR inside Unity Android player through a Java/Kotlin facade and AndroidJavaObject calls.
- New: C# wrapper, callback bridge, IL2CPP/R8 check, iOS plugin (blocked on A).
- Risk: Java cannot call `suspend` cleanly without a facade (inference). Unity's Application and Activity lifecycle differ from the usual Android host. ADR-0034 opt-out and clearQueue must be exposed.

### B4. WebView (native-web bridge, Q150)
- Deferred. Needs spec change, ADR, and public API (`.scratch/signaldock/spec.md:402`).
- Reuse: TS builders in `tracking-plan/generated/typescript/events.ts` (types only; no web SDK, `docs/adr/0021-tracking-plan-codegen.md:36`).
- New: `addJavascriptInterface` surface, page allow-list, origin checks, JS-to-native event mapping.
- Risk: JS interface lets any loaded page send events under the app's key. Needs allow-list design (`.scratch/signaldock/spec.md:402`). Web events have no platform field and would look like Android (see C.1).

## C. Contract or server changes either path would force

1. Platform dimension (needed for A and B). Options:
   - Add optional `platform` to EventIngest. `openapi/contract-v1.yaml:574` has `additionalProperties: false`; unknown fields are rejected with `UNKNOWN_FIELD` (`server/app/src/test/.../ClientReportTest.kt:161`, shape only). `docs/adr/0002-openapi-json-contract.md:13` allows optional additions in v1 only if the server deploys first. Client-report schema would need the same field (`openapi/contract-v1.yaml:776-780`).
   - Encode in `sdkVersion` (for example `ios/0.1.0`). No contract change. Hacky: free text 1-64 chars (`docs/adr/0036-sdk-client-reports.md:13`).
   - Needs a decision and an ADR. Not decided in any design source found.
2. Installation ID format and storage rule for iOS (A risk 2). Decide before implementation. Server rule itself stays (`IngestRules.kt:143`).
3. Tracking plan: `tracking-plan/generate.mjs:14-17` (types), `:84` (kotlin()), `:103` (typescript()), `:147` (outputs). A Swift output is a new function plus outputs. `scripts/verify-contract.sh:74-86` diffs the whole output dir, so new files are checked automatically. `manifest.json` is language-neutral and can feed Swift.
4. Codegen: add a Swift generator to `scripts/codegen.sh` (after `:50`), and a Swift template for the oneOf PropertyValue. Kotlin template override is at `scripts/codegen.sh:41`.
5. Verify gate: add an iOS step to `scripts/verify-contract.sh` (step list `:31-74`) and an iOS row in `scripts/check-repo-rules.sh` for version consistency. Pre-push runs verify only when code changed (`AGENTS.md:9-10`).
6. Client reports: `POST /v1/client-reports` is new and needs no change for iOS, but counts from iOS would sit under sdkVersion without platform (`docs/adr/0036-sdk-client-reports.md:11,14`).
7. Server: no Android-specific code found. grep of `server/` (non-test) for user-agent, platform, or sdk name returns only `sdkVersion` (`server/app/.../RequestParsing.kt:162`, `:198`). No server change needed beyond C.1.
8. No change to headers or auth: Bearer collection key and contract header are language-neutral (`openapi/contract-v1.yaml:496-498`).

Open decisions (ask one at a time, per AGENTS.md): C.1 platform field (a or b); iOS installation ID storage.
