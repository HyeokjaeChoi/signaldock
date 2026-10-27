# 23. Deploy a new demo tag so the PR #232 Compose changes reach the VM

Status: ready-for-human
Type: task
Blocked by: None

## Scope and completion criteria

Found in issue 19. The Dashboard healthcheck and restart policy from PR #232 are in the Compose files. The VM uses the Compose files of the deployed tag `demo-v0.1.1`, so they reach the VM only with the next `demo-v*` tag. Not deployed yet.

Owner decision: tag now (runbook sections 11 and 12), or wait for the next change. Only the owner pushes the tag.
