# 15. Simulator durability script

Status: claimed
Type: task
Blocked by: 14

## Scope and completion criteria

Spec D15. ADR-0011, ADR-0029.

0. Phase mechanism: the script drives SDK phases across process boundaries, like the Android `DurabilityPhases` (ADR-0011). Choose the mechanism (for example launch arguments read only by a debug build of `consumer-ios`, or a separate test host app) and record it in an ADR-0047 amendment before the script. Release builds must not contain the phase code.
1. `scripts/simulator-durability.sh <udid> [case ...]` (local only, 100755): kill during store, kill during send, restart, update install, uninstall and install (new installation ID), opt-out, offline. It refuses a device that is not a Simulator.
2. Pass rule per case: no lost event, no server duplicate by event ID, queue state as expected; read from the server query API.
3. Backup restore and full disk are printed as not run (spec section 5).

Verification:
- One full run; the output and environment record (Xcode, runtime, commit) in the issue file.

## Verification record (2026-10-29)

Environment: macOS 26 (Darwin 25.6.0), Xcode 27.1 (27A9275), iOS 27.0 Simulator runtime, device iPhone 17 (`9C45D335-A19B-43AD-A810-A2530B84B434`, shut down at the end), code commit `b42a92f` (the run used this tree on top of merge `f8b8ee4`, which merges `origin/ios/14-consumer-app`). Server: Compose project `sdverify15` (own env file from `.env.example`, ports 18082/15434/15175; `signaldock-phone` not touched), removed afterwards with `docker compose -p sdverify15 --env-file <env> down -v`.

Command (keys from the scratch env file, `SIGNALDOCK_ENDPOINT=http://127.0.0.1:18082`):

```
IOS_WERROR=1 scripts/simulator-durability.sh 9C45D335-A19B-43AD-A810-A2530B84B434
```

It built a fresh ZIP and the Debug and Release consumer app (`BUILD SUCCEEDED` twice, warnings as errors), found no `SIGNALDOCK_PHASE` in the Release binary, and ran all seven cases (exit 0):

| Case | Result |
|---|---|
| kill_store | `kill -9` during a burst against a closed port: 793 events reported stored, `integrity_check` ok, queue held all 793, delivered after restart: 793 at the server, 0 duplicates, 0 lost |
| kill_send | `kill -9` with 222 events already at the server and 290 stored by the app: after restart 290 at the server, 0 duplicates, 0 lost; the drain ACK said accepted 1, duplicate 3 (events that the server had committed before the kill) |
| restart | same installation ID, same 5 queued event IDs, then 5 at the server once |
| update | `simctl install` over the app: same as restart |
| reinstall | new installation ID, empty queue, nothing of the old installation at the server |
| optout | three distinct processes: opt-out kept across the kill, marker file `opt-out` present, no request while opted out, removed after `setOptOut(false)`, the resumed event delivered once |
| offline | 30 events stored against a closed port, `retry_state` failureCount 1 persisted, kill, restart online: 30 at the server once |

Printed as `not run`: backup restore, full disk (spec section 5). Two earlier full runs (before the final one) gave the same result.

Also: `scripts/check-repo-rules.sh` exit 0; `IOS_WERROR=1 scripts/verify-ios.sh lint` exit 0; `shellcheck scripts/simulator-durability.sh` clean.
