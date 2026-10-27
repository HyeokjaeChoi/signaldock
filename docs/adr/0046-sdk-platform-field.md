---
status: accepted
---

# SDK platform field on the wire (iOS SDK spec D2 to D5)

On 2026-10-27 UTC the owner agreed to add an iOS SDK (`.scratch/ios-sdk/spec.md`, log in `.scratch/ios-sdk/decisions.md`, Q2, Q10, Q12). The server and the Dashboard must then tell events of the two platforms apart. This ADR records the contract, server, and Dashboard side. [ADR-0047](0047-ios-sdk.md) records the iOS SDK.

## Context

- The wire has no platform. `EventIngest` in `openapi/contract-v1.yaml` has `additionalProperties: false` and one SDK field, `sdkVersion`. The client report body also has only `sdkVersion`.
- Version `0.1.0` of the iOS SDK and version `0.1.0` of the Android SDK would look the same to the server.
- The kill switch ([ADR-0037](0037-server-collection-kill-switch.md) decision 1) matches the exact `sdkVersion` string (`server/app/src/main/kotlin/local/signaldock/server/CollectionControl.kt`). Blocking iOS `0.1.0` would also block a future Android `0.1.0`.
- The contract is strict ([ADR-0002](0002-openapi-json-contract.md), [ADR-0007](0007-dashboard-query-api.md)). A server that does not know a field rejects it with `UNKNOWN_FIELD`, and the SDK holds its queue ([ADR-0006](0006-sdk-delivery-decisions.md)).
- Only Android SDK `0.1.0` to `0.3.0` exist. None of them send a platform.

## Decision

1. **Field (D2).** Add the optional field `sdkPlatform` to `EventIngest` and to the client report body. It is an enum with the values `android` and `ios`. It sits next to `sdkVersion`. The change is additive and the contract version stays 1 ([ADR-0002](0002-openapi-json-contract.md), [ADR-0022](0022-server-tracking-plan-status.md) for the same pattern). The event list response, the event detail response, and the client report count items carry `sdkPlatform` as a required field. The server always fills it, and responses are tolerant ([ADR-0007](0007-dashboard-query-api.md)).
2. **A missing field means `android` (D3).** The server stores `android` when `sdkPlatform` is absent. Only Android SDK `0.1.0` to `0.3.0` omit it.
3. **Rule for every SDK.** From now on every SDK must send `sdkPlatform`. The wire corpus tests of each SDK (`fixtures/sdk-wire/<platform>/`, [ADR-0027](0027-request-400-contract-only.md), [ADR-0047](0047-ios-sdk.md) D10) check that the field is present. The server default exists only for old Android SDKs. No new SDK may use it.
4. **Migration V4.** `V4` adds the column `sdk_platform` to `events` and to `client_reports`. It fills old rows with `android`. The column is not null.
5. **Kill switch (D4).** The kill switch blocks by the pair (platform, version). Each item of `SIGNALDOCK_BLOCKED_SDK_VERSIONS` is `platform:version`, for example `ios:0.1.0`. An item with no prefix means `android`, so current settings keep their meaning. The prefix must be exactly `android` or `ios` (case-sensitive); any other prefix, for example `iOS`, is a malformed value and fails the start. The other rules of [ADR-0037](0037-server-collection-kill-switch.md) do not change.
6. **Filter (D5).** The optional query parameter `sdkPlatform` (`android` or `ios`) goes on the five endpoints: `/v1/events`, `/v1/event-counts/by-name`, `/v1/event-counts/by-time`, `/v1/event-counts/by-plan-status`, and `/v1/client-report-counts`. The first four are the endpoints that take `installationId`. `/v1/client-report-counts` has no `installationId`, but it gets the filter too. Its counts group by (platform, SDK version) and (platform, SDK version, reason). The 100-row limit (ADR-0036 decision 9) orders by count descending, then platform, SDK version, and reason ascending, so ties stay stable (today `QueryStore.kt` orders by total, SDK version, reason).
7. **Dashboard.** The Dashboard shows the platform in the event list and in the detail. It adds the platform to the shared filter set.
8. **Order (spec section 4).** Contract, then server, then Dashboard. The Android SDK `0.4.0` sends the field. It and the iOS SDK `0.1.0` are released only after the server with `sdkPlatform` support is deployed. This includes the cloud demo server ([ADR-0043](0043-cloud-demo-hosting-and-deploy.md)) when a released SDK points at it. Parameters are strict, so the server deploys before the Dashboard. Pushing a release tag needs the approval of the owner.

### Missing field: `android` or `unknown`

| | `android` (chosen) | `unknown` (rejected) |
|---|---|---|
| Old Android events in the `android` filter | Yes | No. They drop out of the filter. |
| Kill-switch key for old Android versions | `0.2.0` or `android:0.2.0` | A key such as `unknown:0.2.0` that the operator must learn |
| Response enum | `android`, `ios` | Needs a third value that no SDK sends |
| Wire compatibility | Same | Same |
| Risk | A future SDK that forgets the field counts as Android | None for that case |

The risk is handled by decision 3. The wire fixture tests fail when an SDK omits the field.

## Consequences

- A new column and a migration. A released migration is frozen.
- `fixtures/contract/contract-v1.baseline.yaml` changes on purpose in the contract PR ([ADR-0002](0002-openapi-json-contract.md) amendment of 2026-10-11).
- An older server rejects a request with `sdkPlatform`. The deploy order in decision 8 prevents this.
- The Dashboard cannot use the filter on a server that does not know it. The server deploys first.
- An old item such as `0.2.0` in `SIGNALDOCK_BLOCKED_SDK_VERSIONS` now blocks only Android `0.2.0`.
- Not verified here: no code exists yet. Tickets in `.scratch/ios-sdk/issues/` must verify every decision with tests and record the commands.

## Rejected alternatives

- **Prefix in `sdkVersion` such as `ios/0.1.0`.** It mixes two meanings in one field. Old rows and the Android parser would have two forms.
- **HTTP header.** The server does not store a header per event, and a batch has one header but many events.
- **`unknown` for a missing field.** See the table above.
- **Version only in the kill switch.** It blocks the wrong platform.
- **Display only, no filter.** The owner asked for the filter.

## Revisit when

- A third platform (for example Flutter or React Native) needs a value. Add it to the enum as an additive change.
- Contract 2 starts. Then `sdkPlatform` can become required, and the `android` default can end.
