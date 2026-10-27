# 21. docker-bench 2.2 still WARN with icc false

Status: resolved
Execution: Done (PR #241, 2026-10-27 UTC)
Type: task
Blocked by: None

## Scope and completion criteria

Found in issue 19. After the Docker restart, `/etc/docker/daemon.json` sets `"icc": false`, but docker-bench-security v1.6.1 check 2.2 is still WARN. Not investigated.

Find out why. Read the check in the pinned script and compare with `docker network inspect bridge` and `docker info`. Then fix it, or accept it and record the reason (for example: the check reads only the default bridge, and Compose uses its own network).

## Comments

2026-10-27 UTC. Root cause: runbook section 20 ran `sudo sh docker-bench-security.sh`. The script is `#!/bin/bash`, and `/bin/sh` on the VM is dash. Check 2.2 (and others) use bash `[[ ]]`, which fails under dash, so the check fell to WARN. `icc: false` was correct.

Verified on the VM by the session. `sudo bash docker-bench-security.sh -c check_2_2,check_2_3`: 2.2 PASS, 2.3 WARN (issue 22, wontfix). Full run with `bash` (same pinned commit): Checks 117, Score 27. WARN: 1.1.1, 1.1.3, 1.1.4, 1.1.5, 1.1.6, 1.1.7, 1.1.9, 1.1.10, 1.1.11, 1.1.12, 1.1.14, 1.1.17, 1.1.18, 2.3, 2.9, 2.12, 2.13, 2.16, 4.5, 4.6, 5.9, 5.15, 5.27. The earlier scores 22 and 25 used `sh` and are not comparable.

Fix: runbook section 20 uses `sudo bash` and `sudo rm -rf "$d"` (the script writes root-owned files under `log/`).
