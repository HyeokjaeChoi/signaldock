# 22. docker-bench 2.3 log level

Status: needs-triage
Type: task
Blocked by: None

## Scope and completion criteria

Found in issue 19. docker-bench-security v1.6.1 check 2.3 (log level) is a new WARN, because `daemon.json` now exists.

Decide: set `log-level` in `daemon.json` (needs a Docker restart, see runbook section 9), or accept it and record the reason.
