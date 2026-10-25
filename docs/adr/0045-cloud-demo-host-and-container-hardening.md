---
status: accepted
---

# Cloud demo host and container hardening (cloud-demo spec D12)

The demo ([ADR-0043](0043-cloud-demo-hosting-and-deploy.md)) is on the public internet. This ADR records the hardening of the VM and the containers. The source is `.scratch/cloud-demo/spec.md` (D12).

## Context

- The threat model ([threat-model.md](../engineering/threat-model.md)) assumed a local stack. A public host adds scans, floods, and the cloud metadata server at `169.254.169.254`.
- The VM has no service account (ADR-0043 item 2). The metadata block is an extra layer.
- Gap: none of this is built or verified. Spec section 7 lists open facts: the `DOCKER-USER` rule must match compose bridges (no `-i docker0`) and needs IPv6 and reboot handling; the `nginx-unprivileged` user and port; the JVM with `noexec` on `/tmp`; compose `internal: true` and `pids_limit`.

## Decision

1. Containers run as non-root, with `cap_drop: ALL`, `no-new-privileges`, `read_only` with tmpfs, and memory, pids, and CPU limits (`infra/compose.demo.yml`, planned in ticket 03).
2. PostgreSQL is on an `internal: true` network with the server only.
3. A `DOCKER-USER` rule drops container traffic to `169.254.169.254`. A systemd unit keeps it.
4. The JVM uses `-XX:MaxRAMPercentage`.
5. The host uses `unattended-upgrades`.
6. CI runs a Trivy image scan.
7. An external uptime check sends email alerts.
8. fail2ban watches basic-auth failures.
9. One `docker-bench-security` run is recorded.

## Consequences

- Read-only containers and `noexec` mounts can break the JVM or nginx at start. Ticket 03 must test this locally.
- Detection is thin: no Falco, no audit trail ([ADR-0044](0044-cloud-demo-operation-replaces-adr-0028.md)).
- Automatic OS upgrades can restart services. This is accepted.
- `docker-compose.yml` for local use does not change.

## Rejected alternatives

- Falco (RAM).
- Rootless Docker and userns-remap (friction for 12 weeks).
- cosign verify on the VM (one more moving part).

## Revisit when

- The demo lives past 2027-01-16 UTC or holds real data: reconsider rootless Docker and image signature checks.
