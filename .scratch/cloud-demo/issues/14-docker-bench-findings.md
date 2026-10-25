# 14. docker-bench-security findings (first cloud run)

Status: ready-for-agent
Type: task
Blocked by: None

## Scope and completion criteria

Recorded under ticket 07 item 5 and AGENTS.md rule 7. docker-bench-security v1.6.1 (commit ba0b402e...) ran once on the VM on 2026-10-25 UTC: Checks 117, Score 22.

WARN items:

1.1.1, 1.1.3-1.1.7, 1.1.9, 1.1.10, 1.1.12, 1.1.14, 1.1.17, 1.1.18 (auditd); 2.2; 2.9; 2.12; 2.13; 2.14; 2.15; 2.16; 4.5; 4.6 and 5.27; 5.15; 5.9 (ports, manual).

### (a) To consider

Daemon settings (cheap):

- 2.14: no-new-privileges as daemon default.
- 2.15: `live-restore`.
- 2.2: `icc` is on for the default bridge.
- 2.16: userland proxy. This can affect publishing ports on 127.0.0.1. Test first (`/ready` through host nginx after a restart and a reboot).

Container settings:

- 4.6 and 5.27: the Dashboard has no HEALTHCHECK.
- 5.15: restart policy is not `on-failure:5` on all three containers.
- 5.9 (ports, manual): no 0.0.0.0 ports were found in section 8; re-check after any change.

Completion: for each item, change it and run the section 8 checks and a reboot, or record why not. Keep the change in one PR per purpose (AGENTS.md rule 3).

### (b) Accepted

| Item | Reason | Source |
|---|---|---|
| 2.9 userns | Rootless Docker and userns-remap were rejected. | ADR-0045, rejected alternatives |
| 2.13 central logging | Central logging was rejected. | Spec D11 (OTel Collector, Cloud Logging, Loki rejected) |
| 4.5 content trust | cosign verify on the VM was rejected. | ADR-0045, rejected alternatives |
| 1.1.x auditd | No auditd, no audit trail. Falco was rejected for RAM; auditd has the same cost in RAM and moving parts. | ADR-0045 consequences and rejected alternatives; no design source names auditd |
| 2.12 authz plugin | Single operator. | No design source |
| 1.1.1 separate partition | One 30 GB disk. | Spec D5 gives the single disk; no design source decides on a separate `/var/lib/docker` partition |

Verification: re-run docker-bench-security after the changes in (a); record the new score.
