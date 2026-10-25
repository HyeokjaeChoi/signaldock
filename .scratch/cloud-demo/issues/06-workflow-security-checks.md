# 06. Workflow security checks

Status: resolved
Type: task
Blocked by: 05
Execution: Done (PR #216, 2026-10-25 UTC)

## Scope and completion criteria

Turn the D9 workflow rules into checks (AGENTS.md: a rule becomes a check, not a sentence).

Add to `scripts/check-repo-rules.sh`, with one row each in the AGENTS.md "Enforced rules" table:
1. Every `uses:` in `.github/workflows/*.yml` is pinned by a 40-character SHA (local `./` actions excepted).
2. No workflow uses `pull_request_target` or `workflow_run`.
3. Every workflow has a top-level `permissions:` key. Decide whether `release.yml` keeps `contents: read` or moves to `{}`, and write the reason in the PR.
4. `demo-deploy.yml` runs only on `push` of `demo-v*` tags.
5. `demo-deploy.yml` contains no `id-token:` and no `secrets.`.

Verification:
- Each check fails on a bad sample (show the sample and the output) and passes on the repo.
- `bash scripts/check-repo-rules.sh` exit code 0.
