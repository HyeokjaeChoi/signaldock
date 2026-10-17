# 64. Device test of opt-out across a process restart

Status: resolved
Type: task
Blocked by: None
Execution: Done (merged in PR #173, 2026-10-17 14:58:16 UTC)

## Scope and completion criteria

ADR-0034 (Limits) and issue 58 list a Gap: no device test restarts the process with the opt-out marker file.

1. `DurabilityPhases` has the phases `optout_set`, `optout_check`, and `optout_off`. They skip without `-e phase`.
2. `scripts/device-durability.sh` has the case `optout`. It kills the app process between the phases.
3. `docs/adr/0034-sdk-opt-out.md` has a dated amendment. `docs/engineering/threat-model.md` names the new case.
4. Run `scripts/device-durability.sh <serial> optout` and one older case (`restart`) on an emulator.

## Comments

- Item 4 done on emulator-5556 (API 37) on 2026-10-17: `scripts/device-durability.sh emulator-5556 optout` and `... restart` passed. `connectedDebugAndroidTest`: 33 tests, 0 failed, 16 skipped (phase classes), 17 ran.
- Cause of the earlier failure on emulator-5554: a lock credential on user 0 (`RUNNING_LOCKED`); credential-encrypted storage was never unlocked, so the app data directory was not created. No script sets a credential.
- 2026-10-17 UTC: PR #173 merged 14:58:16Z. Verification in the PR: the `optout` and `restart` cases of `scripts/device-durability.sh` passed on emulator-5556 (AVD faults37, API 37); `connectedDebugAndroidTest` 33 tests, 16 skipped, 17 ran; CI `repo-rules` passed.
