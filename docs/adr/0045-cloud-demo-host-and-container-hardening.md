---
status: accepted
---

# Cloud demo host and container hardening (cloud-demo spec D12)

The demo ([ADR-0043](0043-cloud-demo-hosting-and-deploy.md)) is on the public internet. This ADR records the hardening of the VM and the containers. The source is `.scratch/cloud-demo/spec.md` (D12).

## Context

- The threat model ([threat-model.md](../engineering/threat-model.md)) assumed a local stack. A public host adds scans, floods, and the cloud metadata server at `169.254.169.254`.
- The VM has no service account (ADR-0043 item 2). The metadata block is an extra layer.
- Status: the container part (items 1, 2, 4) is in `infra/compose.demo.yml` and was verified locally in ticket 03: nginx-unprivileged runs as uid 101 on port 8080; the JVM starts with `noexec` `/tmp` and a read-only root; `internal: true` blocks the Dashboard from the DB; `pids_limit` applies. The `DOCKER-USER` rule (item 3) and the rest are still to build (tickets 04, 07). Spec section 7 lists the open facts for them: the rule must match compose bridges (no `-i docker0`) and needs IPv6 and reboot handling.

## Decision

1. Containers run as non-root, with `cap_drop: ALL`, `no-new-privileges`, `read_only` with tmpfs, and memory, pids, and CPU limits (`infra/compose.demo.yml`).
2. PostgreSQL is on an `internal: true` network with the server only.
3. A `DOCKER-USER` rule drops container traffic to `169.254.169.254`. A systemd unit keeps it.
4. The JVM uses `-XX:MaxRAMPercentage`.
5. The host uses `unattended-upgrades`.
6. CI runs a Trivy image scan.
7. An external uptime check sends email alerts.
8. fail2ban watches basic-auth failures. It protects the Dashboard credential, not the collection or query keys.
9. One `docker-bench-security` run is recorded.

## Consequences

- Read-only containers and `noexec` mounts can break the JVM or nginx at start. Ticket 03 must test this locally.
- Detection is thin: no Falco, no audit trail ([ADR-0044](0044-cloud-demo-operation-replaces-adr-0028.md)).
- Automatic OS upgrades can restart services. This is accepted.
- Local `docker-compose.yml` changes only in the Dashboard image (`nginxinc/nginx-unprivileged`) and container port (80 to 8080, host port unchanged), because the unprivileged image cannot bind port 80.

## Rejected alternatives

- Falco (RAM).
- Rootless Docker and userns-remap (friction for 12 weeks).
- cosign verify on the VM (one more moving part).

## Revisit when

- The demo lives past 2027-01-16 UTC or holds real data: reconsider rootless Docker and image signature checks.

## Amendment 2026-10-25 (ticket 07)

- **Trivy (item 6).** The job fails only on fixable CRITICAL and HIGH findings. Images are built with `load`, scanned, then pushed with `docker push`. Builds and scans run with no registry credential: the job logs out of ghcr.io after the tag check and logs in again right before the push. The scanned image is the pushed image. Pushed images carry no provenance attestation. Trivy runs from the `aquasec/trivy:0.76.1` image pinned by digest, with no registry credential and no token (logout before, login after). The `trivy-action` is not used because it passes `github.token` to its installer.
- **fail2ban (item 8).** It reads the JSON access log, not `error.log`, because `error.log` prints attacker-supplied user names raw. A line counts when the status is 401, `upstream_status` is empty (only nginx's own 401; an upstream 401 after auth succeeded is not counted), and `remote_user` is not empty. The two ingest routes are excluded: their 401 comes from the server's collection-key check, and SDK callers get only the per-IP 429. Settings: `maxretry` 5, `findtime` 10m, `bantime` 1h, `backend` auto, nftables ban action, no recidive jail. The Ubuntu default `sshd` jail is turned off: port 22 is open only to the IAP range, so every SSH client address is an IAP forwarder, and a ban would lock the owner out.
- **GoAccess report.** It runs every 10 minutes over 7 days of logs, with `--anonymize-ip`, no WebSocket, and no `--persist`. The VM clock must be UTC. `/reports` answers 301 without auth and leaks nothing.
- **docker-bench-security (item 9)** is pinned to v1.6.1, commit `ba0b402ea56aafbb9a56f09cc13ee629160c58b6`.
- **Uptime check (item 7)** is a Cloud Monitoring public check. No agent and no service account run on the VM.

## Amendment 2026-10-26 (issue 14)

Source: first docker-bench-security run (v1.6.1, score 22). The owner decided on 2026-10-26.

- **Applied.** `/etc/docker/daemon.json` (source `infra/vm/docker-daemon.json`, installed by `install.sh`) sets `live-restore: true` (2.15), `no-new-privileges: true` (2.14), and `icc: false` (2.2). The file is owned by this repo. A change needs a daemon restart. `install.sh` restarts Docker only when no container runs; on a running demo the owner restarts it. That first restart still stops the containers because `live-restore` is not active yet. `icc: false` affects only the default `docker0` bridge; the Compose networks are user-defined and keep container-to-container traffic. The Dashboard has a `healthcheck` (busybox `wget` to `127.0.0.1:8080`) in `infra/compose.demo.yml` (4.6, 5.27).
- **Not applied: restart policy `on-failure:5` (5.15).** A container stopped by a daemon shutdown is not restarted at boot with `on-failure`. The owner often stops and starts the VM, so `unless-stopped` stays.
- **Not applied: `userland-proxy: false` (2.16).** Host nginx reaches the containers only through `127.0.0.1` published ports. Without docker-proxy, Docker needs `route_localnet=1` and loopback DNAT, which interacts with the `DOCKER-USER` rule and the fail2ban nftables ban. The gain is one warning and three small processes; the risk is the whole demo. To change it: add `"userland-proxy": false` to `infra/vm/docker-daemon.json`, restart Docker, and run the runbook section 8 checks, `/ready` through host nginx, and a reboot.
- **Accepted** (2.9, 2.12, 2.13, 4.5, 1.1.x auditd, 1.1.1): reasons in table (b) of `.scratch/cloud-demo/issues/14-docker-bench-findings.md`.
