# 50. SDK reads Deprecation and Sunset headers (contract deprecation warning)

Status: claimed
Type: task
Blocked by: None
Execution: Branch `impl/sdk-deprecation-warning`. SDK part only. The server part is deferred to contract 2.

## Scope and completion criteria

Spec Q159. The decision is in ADR-0030.

1. The SDK reads `Deprecation` (RFC 9745) and `Sunset` (RFC 8594) on every ingest HTTP response.
2. One WARN per process. The state shows in `Diagnostics.contractDeprecation`. Delivery does not change.
3. Invalid or absent headers are ignored.
4. JVM tests in `DeprecationTest` and `TransportTest`.
5. API dump, `CHANGELOG.md`, the integration guide (both languages), and `docs/design-index.md` are updated.

Verification:
- `sdk/gradlew -p sdk :sdk-android:testDebugUnitTest :sdk-android:apiCheck --no-daemon`
- `scripts/verify-contract.sh`
