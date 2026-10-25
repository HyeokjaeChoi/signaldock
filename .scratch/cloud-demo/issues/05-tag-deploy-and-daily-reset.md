# 05. Tag deploy, daily reset, and runbook

Status: resolved
Type: task
Blocked by: 02, 03, 04
Execution: Done (PR #214, 2026-10-25 UTC)

## Scope and completion criteria

Build spec decisions D9, D3, and D7.

1. `.github/workflows/demo-deploy.yml`: only on `push` of tags `demo-v*.*.*`. Top-level `permissions: {}`; the build job has `contents: read` and `packages: write`. Actions pinned by full SHA. Checks the tag format and that the tag is on `main` (same check as `release.yml`). Builds and pushes both images with `GITHUB_TOKEN`; refuses to overwrite an existing image tag. A second job waits until `https://signaldock.stack-line.dev/deployed.txt` shows the tag, or fails with a clear message. No `id-token`, no `secrets.`.
2. `infra/vm/deploy.sh` and its timer (every 5 minutes): fetch tags, pick the newest valid `demo-v*`, check it is on `origin/main` (on the VM, outside the tag's code), pull images, `docker compose up -d` with the override, wait for `/ready`, write the current tag and `deployed.txt`, prune dangling images. On failure, mark the tag failed and roll back to the previous tag. Never touch `.env`.
3. Daily reset at 04:00 Asia/Seoul: remove the DB volume, start again (Flyway creates the schema), run `scripts/demo-seed.py`. Same lock as the deploy, so they do not run together. A disk check runs the reset early above a threshold (value in the PR).
4. `docs/runbook-demo.md`: tag ruleset, GHCR packages to Public (cannot be undone), key rotation, a failed deploy, a manual deploy (`gcloud compute ssh --tunnel-through-iap ... sudo systemctl start signaldock-deploy`), teardown on 2027-01-16 (`terraform destroy`, disable the workflow in a PR, GHCR packages, Cloudflare token, local state).
5. Confirm the Flyway rollback item of spec section 7, and write the result in the runbook.

Verification:
- `actionlint` or the repo workflow checks, `shellcheck` on the scripts.
- On the VM: tag `demo-v0.1.0` deploys; a tag with a failing `/ready` rolls back; a tag not on `main` is refused; the reset timer run leaves seeded data. Record the commands and results.

Note: the on-VM verification items above are owner steps in `docs/runbook-demo.md` and are not run yet.
