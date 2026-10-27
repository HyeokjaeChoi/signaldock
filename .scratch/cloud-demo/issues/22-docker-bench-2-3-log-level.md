# 22. docker-bench 2.3 log level

Status: wontfix
Type: task
Blocked by: None

## Scope and completion criteria

Found in issue 19. docker-bench-security v1.6.1 check 2.3 (log level) is a new WARN, because `daemon.json` now exists.

Decide: set `log-level` in `daemon.json` (needs a Docker restart, see runbook section 9), or accept it and record the reason.

## Comments

2026-10-27, owner decision: accept, do not fix. The Docker daemon default `log-level` is already `info`, which is the value check 2.3 wants. The WARN appeared only because `daemon.json` now exists without the key. Adding the key changes no behavior and needs a Docker restart (runbook section 9). Score cost: 2 points (a scored WARN subtracts 1, a scored PASS adds 1).
