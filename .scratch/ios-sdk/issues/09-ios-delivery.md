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
