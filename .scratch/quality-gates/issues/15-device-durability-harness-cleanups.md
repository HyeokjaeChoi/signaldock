# 15. device-durability.sh harness cleanups (from the review of ticket 06)

Status: ready-for-agent
Type: task

Minor findings of the no-context review of PR 291. No gate is affected.

1. `powerloss`: the host writer process and its temp files are not cleaned up when the case ends early (only the host server is killed by `restore_device`).
2. Exit code 3 means `invalid` in the main loop; any other script that exits 3 inside a case is classed the same way. Use a distinct code or a marker file.
3. The case-name list is repeated in the header, the default list, and the unknown-case check: derive it from one array.
4. `PowerLossPhases.powerloss_check` and `SignalDock.sqlite()` both open the queue database; the four set sizes travel together as a data clump.
5. A WAL-mode check: detect `synchronous=FULL` (issue 14) in the case and say so in the result instead of only in the ADR.
6. `restore` failed once ("backup did not succeed", output not kept); the cause is unknown. The case now prints the `bmgr` output on failure.
