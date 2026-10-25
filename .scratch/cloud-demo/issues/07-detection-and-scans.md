# 07. Detection and scans

Status: resolved
Type: task
Blocked by: 04, 05
Execution: Done (PR #219, 2026-10-25 UTC)
Note: first cloud setup, 2026-10-25 UTC. Ran: uptime check `signaldock-ready` (HTTPS /ready, 5 min, TLS validation, 2xx, 6 global locations, all passing) and alert policy `signaldock-ready down` (fails 5 min) with an email channel to the owner; docker-bench-security v1.6.1 (Checks 117, Score 22; findings in issue 14). Not run: alert test email, GoAccess report, fail2ban ban test. Trivy ran on tag builds: run 40199532328 (demo-v0.1.0) and run 40202103660 (demo-v0.1.1, https://github.com/HyeokjaeChoi/signaldock/actions/runs/40202103660), steps "Trivy scan, server image" and "Trivy scan, Dashboard image" both success.

## Scope and completion criteria

Build the detection part of spec decision D12 and the GoAccess report of D11.

1. Trivy scan of both images in `demo-deploy.yml` before push: fail on fixable CRITICAL and HIGH findings. Action or binary pinned (SHA or checksum).
2. GoAccess: a timer makes a static HTML report from the nginx JSON log, with IP anonymization, served behind basic auth. No real-time WebSocket port.
3. fail2ban jail for basic-auth failures in the host nginx log.
4. Uptime check: the owner creates a GCP Cloud Monitoring uptime check on `/ready` through host nginx (or a public health path) with an email alert. The runbook has the steps. Confirm its price and that the VM needs no agent or service account.
5. Run `docker-bench-security` once on the VM. Fix the cheap findings in this PR or record the rest as issues.

Verification:
- A Trivy run on a tag build (link the run).
- The GoAccess report loads behind basic auth and shows anonymized IPs.
- `fail2ban-client status` after a few failed logins from a test IP.
- The uptime check shows green, and a test alert reaches the email.
- The `docker-bench-security` summary in the PR.
