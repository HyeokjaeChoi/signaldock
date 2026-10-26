# 19. Docker hardening: roll out on the VM and re-run docker-bench

Status: claimed
Type: task
Blocked by: None

## Scope and completion criteria

Follow-up of issue 14 (PR #232), source: ADR-0045 amendment 2026-10-26. No ticket blocks it, but the owner must start the VM and must agree before the Docker restart.

The first rollout of `daemon.json` stops the containers for a short time. Do not restart Docker without the owner's OK.

On the VM:

1. Run `install.sh` over IAP SSH (runbook section 9 command).
2. Restart Docker when a short stop is fine (the owner decides).
3. Run the runbook section 8 checks. Check `/ready` through host nginx and that no container publishes on 0.0.0.0.
4. Reboot the VM. Run the section 8 checks again.
5. Re-run docker-bench-security (runbook section 20). Record the new score in issue 14 or in this issue.

In the repo:

6. Add a short note to runbook section 9 (a `daemon.json` change needs a Docker restart) and to section 20 (re-run docker-bench after a daemon change). PR #232 left this out because another session edited the runbook.

Verification:
- The section 8 checks pass before and after the reboot. The new docker-bench score is recorded.
- `bash scripts/check-repo-rules.sh; echo "exit=$?"` exits 0 after the runbook edit.

## Results (2026-10-26 UTC, 23:4x to 23:5x, run by the main session)

- `install.sh` from `origin/main` (runbook section 9 command) installed `/etc/docker/daemon.json` = `{"live-restore": true, "no-new-privileges": true, "icc": false}`. Containers were running, so it did not restart Docker and left the marker `/etc/docker/daemon.json.pending`.
- The owner agreed to the restart. Docker was restarted under the deploy/reset lock: `sudo flock -w 600 /run/signaldock.lock sh -c 'systemctl restart docker && rm -f /etc/docker/daemon.json.pending'`. After it: `docker info` shows live-restore=true; `docker`, `signaldock-docker-user`, and `nginx` active; DOCKER-USER DROP rule present; public `/ready` 200.
- Section 8 checks, after the restart and again after a reboot (uptime 0 min): no 0.0.0.0 ports (Dashboard 127.0.0.1:5173, server 127.0.0.1:8080, db not published); host metadata 200; container curl to 169.254.169.254 exit 28; DROP rule present; three units active; live-restore=true; public `/ready` 200.
- docker-bench-security v1.6.1 (commit `ba0b402ea56aafbb9a56f09cc13ee629160c58b6`) re-run: Checks 117, Score 25 (was 22). Now passing: 2.14 (no-new-privileges), 2.15 (live-restore). Still WARN: 1.1.1, 1.1.3 to 1.1.7, 1.1.9, 1.1.10, 1.1.11 (new: `daemon.json` now exists, no auditd), 1.1.12, 1.1.14, 1.1.17, 1.1.18, 2.2 (still WARN although `daemon.json` sets icc false; not investigated, issue 21), 2.3 (new: log level, issue 22), 2.9, 2.12, 2.13, 2.16, 4.5, 4.6, 5.15, 5.27, 5.9.
- The Dashboard healthcheck and restart policy from PR #232 are in the Compose files. The VM uses the Compose files of the deployed tag `demo-v0.1.1`, so they reach the VM only with the next `demo-v*` tag. Not deployed yet (issue 23).
- Side observation: after the VM was started, the reset timer ran once at 23:48 UTC (a missed run caught up).
- Step 6 (runbook notes in sections 9 and 20): done in this PR.
