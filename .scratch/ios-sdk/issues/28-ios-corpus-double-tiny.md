# 28. iOS corpus: re-add double_tiny

Status: resolved
Type: task
Execution: Done (PR #261, 2026-10-28 UTC)
Blocked by: the server subnormal PR (relaxes `IngestRules.validNumber` so that the server accepts `5e-324`)

## Scope and completion criteria

Owner decision 2026-10-28: the iOS `all-property-types` wire case does not have `double_tiny` (the smallest subnormal `Double`), because Swift writes `5e-324` and the server accepts only Java's `4.9E-324`.

1. In `sdk-ios/Tests/SignalDockTests/WireCorpusTests.swift`, add `$0.property("double_tiny", Double.leastNonzeroMagnitude)` back to the `all_types` case and remove `knownDifferences`.
2. Remove `knownDifferences` from `server/app/src/test/kotlin/local/signaldock/server/SdkWireCorpusTest.kt`.
3. Regenerate `fixtures/sdk-wire/ios/` (`TEST_RUNNER_UPDATE_SDK_WIRE=1 scripts/verify-ios.sh tsan`) and check that the server corpus test accepts it.
4. Update the "Open: the smallest subnormal" item in the ADR-0047 ticket 09 section.
