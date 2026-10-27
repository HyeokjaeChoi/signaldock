# 15. Simulator durability script

Status: ready-for-agent
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
