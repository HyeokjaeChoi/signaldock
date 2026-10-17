# 65. Client report E2E case

Status: resolved
Type: task
Blocked by: None
Execution: Done (merged in PR #175, 2026-10-17 14:59:01 UTC)

## Scope and completion criteria

Closes the Gap in issue 61 and ADR-0036: "no end-to-end SDK to server to Dashboard run".

1. `scripts/e2e-delivery.sh` and `scripts/e2e/e2e_cases.py` get case 5. The real SDK (consumer app, R8) counts a `server_rejected` drop, sends `POST /v1/client-reports`, and the server stores it. The case reads `GET /v1/client-report-counts` through the Dashboard path.
2. No product code changes. The drop is caused as in case 3 (the fault proxy `mutate` mode).
3. Docs: ADR-0036 Amendment, `docs/reproduce.md`, `docs/engineering/` gap lines, script header.

Verification: `ANDROID_SERIAL=emulator-5556 E2E_SKIP_BUILD=1 scripts/e2e-delivery.sh 5 3 1` (see Comments).

## Comments

- 2026-10-17 UTC: run on emulator-5556 (faults37, API 37, `sdk_gphone16k_arm64`), Compose stack on the default project. `scripts/e2e-delivery.sh 5 1` (with build): case 1 PASS; case 5 first failed because the case 3 helper wanted three events in one batch, but only two events reached the batch (the proxy log shows a rejection and a 204 report each time). Case 5 needs only two events, so the helper takes the batch size as a parameter. Then `E2E_SKIP_BUILD=1 scripts/e2e-delivery.sh 5 3 1`: PASS for cases 1, 3, and 5. CodeRabbit (major): late reports of case 3 or failed attempts could satisfy case 5; fixed with `settle_reports()` and a proxy-log time boundary. Re-run `E2E_SKIP_BUILD=1 scripts/e2e-delivery.sh 3 5 1`: PASS for all three. Case 5: Dashboard `server_rejected` 5 to 6, one new `client_reports` row, proxy saw `POST /v1/client-reports` answered 204.
- Gap: only `server_rejected` is covered end to end. `queue_full`, `storage_error`, `invalid_event`, and `event_too_large` are not caused on a device by this case (the consumer app cannot trigger them without an app change).
- Gap: the Dashboard card itself (browser rendering) is not run; the case reads the same endpoint through the same nginx path.
- 2026-10-17 UTC: PR #175 merged 14:59:01Z. Verification in the PR: `E2E_SKIP_BUILD=1 ANDROID_SERIAL=emulator-5556 scripts/e2e-delivery.sh 3 5 1` passed; after the review fix, case 5 passed again (server_rejected 8 -> 9, one new row, report 204); CI passed.
