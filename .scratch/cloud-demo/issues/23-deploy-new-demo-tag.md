# 23. Deploy a new demo tag so the PR #232 Compose changes reach the VM

Status: resolved
Type: task
Blocked by: None
Execution: Done (PR #242, tag demo-v0.1.3, 2026-10-27 UTC)

## Scope and completion criteria

Found in issue 19. The Dashboard healthcheck and restart policy from PR #232 are in the Compose files. The VM uses the Compose files of the deployed tag `demo-v0.1.1`, so they reach the VM only with the next `demo-v*` tag. Not deployed yet.

Owner decision: tag now (runbook sections 11 and 12), or wait for the next change. Only the owner pushes the tag.

## Comments

2026-10-27 UTC: tag `demo-v0.1.2` failed the Trivy gate (Dashboard image, curl CVE-2026-92392, run 40514456917); no image was pushed. PR #242 fixed the image. Tag `demo-v0.1.3` (run 40518764764): build success, wait-deployed success. On the VM: `/deployed.txt` = `demo-v0.1.3`, `/ready` 200, `docker ps` shows `signaldock-dashboard-1 ... (healthy)`, so the PR #232 healthcheck is live.
