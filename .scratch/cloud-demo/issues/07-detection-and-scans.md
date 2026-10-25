# 07. Detection and scans

Status: resolved
Type: task
Blocked by: 04, 05
Execution: Done (PR #219, 2026-10-25 UTC)
Note: The VM and owner verification items (GoAccess report, fail2ban ban, a Trivy run on a real tag, uptime check and alert email, docker-bench-security) are owner steps in `docs/runbook-demo.md` sections 18-20 and are not run yet.

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
