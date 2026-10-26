# 14. iOS consumer and demo app

Status: ready-for-agent
Type: task
Blocked by: 10, 11, 13

## Scope and completion criteria

Spec D12. ADR-0009.

1. `compatibility/consumer-ios`: SwiftUI app; adds the SDK only as the unzipped ZIP folder (a path given to the build, like `-Psignaldock.repo`); no reference to `sdk-ios/` sources.
2. Screens: product view, add to cart, simulated purchase (success and failure), using the tracking plan Swift output; a diagnostics screen; endpoint is the local Compose server.
3. CI builds it in Debug and Release from a freshly built ZIP.
4. `docs/design-index.md` updated if needed.

Verification:
- Both CI builds; a Simulator run against Compose: events reach the Dashboard with platform `ios` (screenshot in the issue file).
