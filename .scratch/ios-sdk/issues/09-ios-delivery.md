# 09. iOS delivery: sender, ACK, retry, triggers

Status: claimed
Type: task
Blocked by: 02, 04, 08

## Scope and completion criteria

Spec D1, D8. ADR-0006, ADR-0030, ADR-0047.

1. Sender actor: batch group by contract version and installation ID, body from stored JSON bytes, the ADR-0006 final-result table, Retry-After, backoff, hold, 413 split, ACK in one transaction. `URLSession` with the four timeouts and no redirects.
2. Contract deprecation headers (ADR-0030).
3. Triggers: 20 events or 10 s; launch and foreground return when the next-attempt time has passed; one drain on entering the background inside `beginBackgroundTask` (start before work, end once; expiration cancels the request).
4. Wire corpus (ADR-0047 D10): `fixtures/sdk-wire/ios/` with the same case names as `android/`, byte-locked to the iOS output with a regeneration switch; the server corpus test parses it; a comparison test checks equal JSON values per case name (numbers by value, `sdkPlatform` and `sdkVersion` excluded).
5. Tests: deterministic transport fake and injected clock for every row of the ADR-0006 table; resend keeps bytes and event IDs; expiration during a request loses nothing and sends no duplicate after relaunch; no sleeps.

Verification:
- `xcodebuild test` (TSan), test count in the PR; a manual run against Compose with the command and result.

## Verification run 2026-10-28

Corpus against a local Compose stack on origin/main (9d11c66 plus this PR's corpus change): `docker compose -p sdverify --env-file <.env.example copy, ports 18080/15432> up -d --build --wait db server`, then each of the 9 files in `fixtures/sdk-wire/ios/` as the body of `POST http://127.0.0.1:18080/v1/events:batch` (`Authorization: Bearer <collection key>`, `SignalDock-Contract-Version: 1`, `Content-Type: application/json`). All 9 answered HTTP 200 with every item `accepted` and none rejected (80 events: 1, 2, 16, 50, 4, 2, 1, 1, 3 per file in file name order). The `events` table had 80 rows, all `sdk_platform = 'ios'`; `double_tiny` of `all-property-types` is stored as the value 5E-324. A second send of the same files stored no new row. `docker compose -p sdverify down -v` removed the stack; the `signaldock-phone` project was not touched.

Not run: the SDK itself against Compose. It needs a host app (ticket 14), so this ticket stays `claimed`.
