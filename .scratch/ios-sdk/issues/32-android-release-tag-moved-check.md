# 32. Android release workflow: check that the tag still points to the built commit

Status: ready-for-agent
Type: task
Blocked by: None

## Scope and completion criteria

`.github/workflows/release.yml` (ADR-0024) builds in `build` and creates the release in `publish`. If someone moves the tag between the two jobs, `publish` creates a release for the new tag target with a ZIP built from the old commit. `release-ios.yml` (iOS ticket 13) closes this gap; the Android workflow has the same gap.

1. Before `gh release create`, the `publish` job compares the commit of the tag (`gh api repos/$GITHUB_REPOSITORY/commits/$TAG --jq .sha`) with `$GITHUB_SHA` and fails on a difference.
2. Add `concurrency: {group: release-${{ github.ref }}, cancel-in-progress: false}` at the top level.
3. Give `GH_TOKEN` only to the steps that need it (ADR-0024 keeps `build` free of a write token; the token is `contents: read` there).
4. Record the change in ADR-0024 (amendment).

Verification:
- `actionlint`, `scripts/check-repo-rules.sh`. The real run is the next Android release tag.
