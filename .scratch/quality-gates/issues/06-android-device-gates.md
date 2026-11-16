# 06. Android device gates: StrictMode, power loss, nightly durability matrix, main-thread cost

Status: resolved
Type: task
Execution: Done (PR #291)
Blocked by: none

## Scope and completion criteria

Spec gates A3, A4, A5, A6. ADR-0049 decisions 2, 5, 6; ADR-0011, ADR-0023, ADR-0029.

1. A3 (PR tier): a JUnit rule sets a main-thread `StrictMode` policy (`detectAll`, `penaltyDeath`) around public SDK calls (`initialize`, `track`, opt-out, `clearQueue`, `diagnostics`, the foreground delivery drain) in the instrumented tests that the `device-test` job already runs. A violation kills the test process, so the job fails. The `device-test` job YAML does not change. The SDK has no public `flush()`: the nearest call is the internal foreground drain (`delivery.drainForeground()`), which the rule covers.
2. A6 (nightly): `FaultPhases`-style phases for power loss. Write events, stop delivery (airplane mode, WorkManager work cancelled), `PRAGMA wal_checkpoint(FULL)` from test code with `busy = 0` and `log = checkpointed` asserted (busy makes the run `invalid`, a harness error), write more events; the host script SIGKILLs the emulator process and cold-boots without a snapshot (data partition kept). Then: queue opens and `PRAGMA integrity_check` = `ok`; the server stores each `eventId` once and answers resends `duplicate`, never `conflict`; no event committed before the checkpoint is lost. Loss after the checkpoint and resend counts are recorded, not gated. `synchronous=NORMAL` stays.
3. A5 (nightly): `scripts/device-durability.sh` runs all cases (the existing 11 and the new `powerloss`) on CI emulators at API 24 and API 37 (x86_64; if no API 37 x86_64 image is available on the runner, the newest one, recorded). Writes the per-case JSON of ticket 05 (`[{name, result, seconds}]`).
4. A4 (nightly): a main-thread `track()` p99 budget. The phase `MeasurePhases.main_thread` measures it; the budget is the measured value plus a stated margin (ADR-0049 decision 2). With no run on the nightly runner class yet, the budget stays unset and a follow-up issue records it.
5. The nightly jobs go into `.github/workflows/nightly.yml` only after ticket 05 merges (rebase). `AGENTS.md` and `docs/design-index.md` change only after ticket 05 merges.
6. ADR-0049 dated amendment: run IDs, cases passed per API, lost-after-checkpoint and resend counts, StrictMode violations found and fixed.

## Notes

- Files this ticket owns: `sdk/sdk-android/src/androidTest/**`, `scripts/device-durability.sh`, `scripts/ack-server.py` (the host server of the airplane and power-loss cases).
- Does not change: `synchronous=NORMAL`, the `device-test` job, product code (unless a StrictMode finding is small; a large fix is a separate PR).

## Comments
