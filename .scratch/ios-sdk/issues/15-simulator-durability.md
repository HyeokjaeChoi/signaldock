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

## Verification record (2026-10-29, after review round 1)

Environment: macOS 26 (Darwin 25.6.0), Xcode 27.1 (27A9275), iOS 27.0 Simulator runtime, device iPhone 17 (`9C45D335-A19B-43AD-A810-A2530B84B434`, shut down at the end). Code: the review-round tree on top of merge `8a43199` (merges `origin/ios/14-consumer-app` at `e74c258`). Server: Compose project `sdverify15` (own env file from `.env.example`, ports 18082/15434/15175; `signaldock-phone` not touched), removed afterwards with `docker compose -p sdverify15 --env-file <env> down -v`.

Command (keys from the scratch env file, `SIGNALDOCK_ENDPOINT=http://127.0.0.1:18082`):

```
IOS_WERROR=1 scripts/simulator-durability.sh 9C45D335-A19B-43AD-A810-A2530B84B434
```

It built a fresh ZIP and the Debug and Release consumer app (`BUILD SUCCEEDED` twice), read 5263 strings of the Release binary and found no `SIGNALDOCK_PHASE`, and ran all seven cases (exit 0). The Release check was proven on a text file that holds the marker followed by 20 MB of data: the earlier `strings | grep -q` form under `pipefail` reported "not found" (SIGPIPE), the form that writes `strings` to a file first counts 1.

| Case | Shown |
|---|---|
| kill_store | `kill -9` during a burst against a closed port (acked.log still growing): 1763 events reported stored, `integrity_check` ok, all in the queue; after restart online the server holds all of them (1 more than reported: the event in flight, allowed) |
| kill_send | `kill -9` with 645 events at the server and 704 stored by the app (delivery partial, asserted); after restart 705 at the server, 0 lost, 0 unknown; drain ACK accepted 1, duplicate 2 (recorded, not asserted) |
| restart | same installation ID, same 5 queued event IDs, then 5 at the server |
| update | `simctl install` over the app: same as restart |
| reinstall | new installation ID, empty queue, nothing of the old installation at the server |
| optout | three distinct processes; opt-out kept across the kill (marker file `opt-out` present, `isOptedOut` true, `track` returned optedOut, nothing queued); the server stored no event of the installation while opted out (request counts are not observed); removed after `setOptOut(false)`; the resumed event delivered |
| offline | 30 events stored against a closed port; persisted `retry_state` failureCount 1 asserted (>= 1) after the kill; after restart online 30 at the server |

Printed as `not run`: backup restore, full disk (spec section 5). A repeated event ID cannot be stored by the server, so "no server duplicate" is not asserted; see ADR-0047 "Ticket 15 results".

Also: `scripts/check-repo-rules.sh` exit 0; `IOS_WERROR=1 scripts/verify-ios.sh lint` exit 0; `shellcheck scripts/simulator-durability.sh` clean.

Re-run after PR 265 review fixes (drain needs `storageAvailable`, the auto run is off during a phase, `kill_send` kills at the first partial sample), 2026-10-29: `IOS_WERROR=1 scripts/simulator-durability.sh 9C45D335-A19B-43AD-A810-A2530B84B434` against Compose `-p sdverify265` (ports 18084/15436/15177): exit 0, all seven cases pass; `kill_send` killed at 295 events at the server of 354 stored; `offline` failureCount 2; Release check: no marker. `down -v` done.
