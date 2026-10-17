# 64. Device test of opt-out across a process restart

Status: claimed
Type: task
Blocked by: None
Execution: Branch `test/opt-out-restart-device`. PR open; not merged.

## Scope and completion criteria

ADR-0034 (Limits) and issue 58 list a Gap: no device test restarts the process with the opt-out marker file.

1. `DurabilityPhases` has the phases `optout_set`, `optout_check`, and `optout_off`. They skip without `-e phase`.
2. `scripts/device-durability.sh` has the case `optout`. It kills the app process between the phases.
3. `docs/adr/0034-sdk-opt-out.md` has a dated amendment. `docs/engineering/threat-model.md` names the new case.
4. Run `scripts/device-durability.sh <serial> optout` and one older case (`restart`) on an emulator.

## Comments

- Gap: item 4 was not run. Local emulator-5554 (API 37, `signaldock-api37`) has user 0 in state `RUNNING_LOCKED` with a lock credential (`locksettings verify`: "User has a lock credential"). Credential-encrypted storage is not unlocked, so `/data/user/0/<pkg>` is never created, and the SDK fails with `StorageFailure(IOException)`. No script in the repo sets a credential. Fix: remove the credential in the emulator (Settings, Security, Screen lock, None) or start the AVD with `-wipe-data`; then run the commands in item 4.
