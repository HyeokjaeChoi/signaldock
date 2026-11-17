# device-durability kill_store has the same fixed-delay race as ios-durability

Status: needs-triage

`scripts/device-durability.sh:121-126` runs `sleep 12`, then a 1 s growth check on the burst. The burst (`DurabilityPhases.kt`, `Thread.sleep(2)`) ends by itself at the queue limit `maxEvents = 10_000` (`Storage.kt`). A stall of the runner or `adb` of about 8 s or more runs the check after the burst has ended, like ios-durability run 44006730500. Fix as in the iOS case (wait for N reported events, then growth, then kill). Owned by the device-durability session.
