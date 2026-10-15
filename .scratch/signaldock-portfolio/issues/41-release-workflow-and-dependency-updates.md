# 41. Release workflow and dependency updates

Status: claimed
Type: task
Blocked by: None
Execution: Implemented on branch impl/release-workflow, PR pending

## Scope and completion criteria

Spec Q149 made the first release by hand. Now a tag makes the release, and Dependabot proposes updates. The decisions are in ADR-0024.

1. `.github/workflows/release.yml` runs on a push of a tag `v*.*.*`. It verifies the tag (format, `sdkVersion` through `scripts/release.sh <version> --check`, on `main`, release notes exist, no release yet), builds with `scripts/release.sh`, and a second job with `contents: write` creates the GitHub Release with `dist/*.zip`, `dist/*.sha256`, and the notes from `docs/releases/v<version>.md`. It never overwrites a release and does not run for the existing `v0.1.0`. Third-party actions are pinned to a SHA.
2. `.github/dependabot.yml`: weekly, grouped by blast radius (SDK runtime dependencies apart from build tools), small PR limits, for gradle (four builds), npm (`/dashboard`, `/perf/report`), github-actions, docker, and docker-compose. Known limit: the root version catalog is invisible to Dependabot (ADR-0024 item 6).
3. Docs: ADR-0024, `docs/releases/README.md` (how to cut a release), `docs/design-index.md`, `CHANGELOG.md`.

Verification:
- YAML parse of both files; `scripts/release.sh 0.1.0 --check` passes and `9.9.9 --check` fails; the tag format check in a shell; `scripts/check-repo-rules.sh`; `coderabbit review --base main --deep --agent`.
- Only a real tag push proves the workflow end to end (not done here).

## Comments
