# 21. docker-bench 2.2 still WARN with icc false

Status: needs-triage
Type: task
Blocked by: None

## Scope and completion criteria

Found in issue 19. After the Docker restart, `/etc/docker/daemon.json` sets `"icc": false`, but docker-bench-security v1.6.1 check 2.2 is still WARN. Not investigated.

Find out why. Read the check in the pinned script and compare with `docker network inspect bridge` and `docker info`. Then fix it, or accept it and record the reason (for example: the check reads only the default bridge, and Compose uses its own network).
