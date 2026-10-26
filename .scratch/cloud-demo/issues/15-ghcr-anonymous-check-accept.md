# 15. Runbook section 11: anonymous GHCR check accepts only OCI manifests

Status: resolved
Type: task
Blocked by: None
Execution: Done (PR #231, 2026-10-26 UTC)

## Scope and completion criteria

Minor finding from the PR 223 review, recorded under AGENTS.md rule 7.

The anonymous manifest check in `docs/runbook-demo.md` section 11 step 3 sends only OCI media types in `Accept`. A package that holds a Docker schema2 manifest can answer `404` although it is public. Add the Docker schema2 media types to `Accept`, and say what a result other than `200` or `401`/`403` means.

Verification: run the check against both demo images and show `200`.
