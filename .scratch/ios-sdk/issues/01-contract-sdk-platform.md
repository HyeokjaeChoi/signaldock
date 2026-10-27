# 01. Contract: `sdkPlatform` field and filter

Status: ready-for-agent
Type: task
Blocked by: None

## Scope and completion criteria

Spec D2, D3, D5. ADR-0046.

1. `openapi/contract-v1.yaml`: optional `sdkPlatform` (enum `android`, `ios`) on `EventIngest` and on the client report request body, next to `sdkVersion`, with a description that says a missing value means `android` (D3). Required `sdkPlatform` on the event list item and the event detail response. Client report count items carry `sdkPlatform`. New parameter `SdkPlatformFilter` on `/v1/events`, `/v1/event-counts/by-name`, `by-time`, `by-plan-status`, and `/v1/client-report-counts`.
2. Regenerate Kotlin and TypeScript models (`scripts/codegen.sh`). Update the `oasdiff` baseline only as `scripts/verify-contract.sh` documents; the change must be additive (no breaking change reported).
3. Do not change `fixtures/sdk-wire`: it is the byte-locked Android output (`WireCorpusTest.kt`), and Android sends the field only from ticket 04.
4. The contract version stays `1`.
5. Update every call site that compiles against the changed generated models, so this ticket merges alone (server compile, Dashboard `tsc` and tests pass):
   - Server `QueryRoutes.kt` (`EventReceiptSummary`, `EventReceiptDetail`, `ClientReportCount`): set `sdkPlatform` to `android`. This is true until ticket 02: the strict parser (`RequestParsing.kt`, `ignoreUnknownKeys = false`, `UNKNOWN_FIELD`) rejects a sent `sdkPlatform`, so every stored row is from Android. Ticket 02 replaces the constant with the stored value.
   - Dashboard fixtures and type checks that build these models (`App.test.tsx`, `xss.test.tsx`, `language.test.tsx`, `contract-check.ts`, and any other `tsc` error): add `sdkPlatform`. No UI change (ticket 03).
   - No other server behavior changes here: storage, default, kill switch and filters are ticket 02.

Verification:
- `server` build and test task, Dashboard `tsc`, test and build (commands and counts in the PR).
- `scripts/verify-contract.sh` (real exit code, oasdiff output in the PR).
- `bash scripts/check-repo-rules.sh`.
