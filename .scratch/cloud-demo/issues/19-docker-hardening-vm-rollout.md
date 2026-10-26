# 19. Docker hardening: roll out on the VM and re-run docker-bench

Status: ready-for-human
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
