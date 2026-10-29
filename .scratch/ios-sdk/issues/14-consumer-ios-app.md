# 14. iOS consumer and demo app

Status: resolved
Execution: Done (PR #264, 2026-10-29 UTC)
Type: task
Blocked by: 10, 11, 13

## Scope and completion criteria

Spec D12. ADR-0009.

1. `compatibility/consumer-ios`: SwiftUI app; adds the SDK only as the unzipped ZIP folder (a path given to the build, like `-Psignaldock.repo`); no reference to `sdk-ios/` sources.
2. Screens: product view, add to cart, simulated purchase (success and failure), using the tracking plan Swift output; a diagnostics screen; endpoint is the local Compose server.
3. CI builds it in Debug and Release from a freshly built ZIP.
4. `release-ios.yml` builds `consumer-ios` Debug and Release from the unzipped ZIP (spec D13 item 9, parity with Android `release.sh` -> `verify-consumer.sh`).
5. `docs/design-index.md` updated if needed.

Verification:
- Both CI builds; a Simulator run against Compose: events reach the Dashboard with platform `ios` (screenshot in the issue file).

## Verification run 2026-10-29

Details and "decided here" choices: ADR-0047 "Ticket 14 results".

- `IOS_WERROR=1 scripts/verify-consumer-ios.sh`: exit 0. Fresh ZIP, unzip to `compatibility/consumer-ios/.sdk`, Debug and Release `** BUILD SUCCEEDED **` for the iOS Simulator (Xcode 27.1).
- `scripts/check-repo-rules.sh`: exit 0, no output. `IOS_WERROR=1 scripts/verify-ios.sh lint`: exit 0, no finding.
- Simulator run against Compose (`docker compose -p sdverify14`, ports 18080/15432/15173): the Debug app (iPhone 18 Pro, iOS 27.0) launched with `SIMCTL_CHILD_SIGNALDOCK_AUTORUN=1` sent 4 events. `GET /v1/events?...&sdkPlatform=ios` returned `product_viewed`, `add_to_cart`, `checkout_started`, `purchase_completed`, all platform `ios`, version `0.1.0`, plan status `planned`. Diagnostics screen: `lastAck` accepted 4. Screenshot: [14-consumer-ios-simulator.png](14-consumer-ios-simulator.png). Stack removed with `down -v`; Simulator shut down.
- Dashboard (same stack, `http://127.0.0.1:15173/`, period "today"): the four events are listed with SDK platform `iOS`, version `0.1.0`, plan status planned. Screenshot: [14-dashboard-ios-events.jpg](14-dashboard-ios-events.jpg).
- Not run: the CI jobs on GitHub (`ios-consumer`, `release-ios.yml` step); iOS 15 runtime.
