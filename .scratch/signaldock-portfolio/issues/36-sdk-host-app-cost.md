# 36. SDK host app cost (APK size, initialization time, PSS)

Status: resolved
Type: task
Blocked by: None
Execution: Done (merged in PR #92, 2026-10-14 UTC)

## Scope and completion criteria

Spec Q151: measure what the SDK costs a host app. Three numbers: APK size increase, initialization time, memory (PSS). The method is in ADR-0020. Emulator numbers only.

1. `compatibility/consumer-android` builds a no-SDK baseline with `-Psignaldock.sdk=false` (no dependency, no SDK call). The default build still uses the SDK, and `scripts/verify-consumer.sh` passes.
2. `scripts/host-cost-measure.sh <serial>` builds both variants from the Maven ZIP, measures them on one emulator, and writes `perf/sdk-results/host-cost-api<api>/` (CSV files, `dependencies.txt`, `meta.json`, `summary.md`).
3. One committed run on the API 37 emulator. The README (both languages) states the headline numbers and links the summary. `CHANGELOG.md` and `docs/design-index.md` are updated.

Verification:
- `scripts/check-repo-rules.sh`; `scripts/verify-contract.sh`; `scripts/verify-consumer.sh`; `sdk/gradlew -p sdk test --no-daemon`.
- `scripts/host-cost-measure.sh emulator-<port>` on an API 37 emulator.

## Comments
