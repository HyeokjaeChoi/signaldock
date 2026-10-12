# 20. SDK durability on devices (part of issue 07)

Status: ready-for-agent
Type: task
Blocked by: None
Execution: Not started

## Scope and completion criteria

Spec section 7 rows "Offline recovery", "Concurrency", "Collection cancellation", "Capacity boundaries", and "Installation lifetime", with the related Q110-Q125 agreements. Run them as instrumented tests on emulators: the minimum API (24) and the selected latest stable version (API 37, AVD `signaldock-api37`), as section 7 requires. Create an API 24 AVD for this.

- Offline recovery: keep 1,000 events with their stored IDs and content across a real process restart and reconnection.
- Concurrency: no queue loss when foreground delivery and the Worker contend, are interrupted, and resume.
- Collection cancellation: cancellation before storage, during storage, and during result delivery after commit; required WARN logs and retention.
- Capacity: 16 KiB event, 256 KiB batch, 10,000 events, 16 MiB queue, concurrent insertion, space recovery after QueueFull.
- Installation lifetime: data kept on restart and app update; excluded on data clear, reinstall, and supported restore.

- Q110-Q118 (initialization results, retry, shared attempts, process-lifetime scope, log level and destination, invalid configuration): confirm each has a test (JVM or instrumented); add missing ones.

Done when the tests pass on both AVDs and every Q110-Q118 item maps to a passing test, with commands, results, and environment (device, OS, build mode) recorded here.

## Comments

2026-10-12: Split from issue 07.
