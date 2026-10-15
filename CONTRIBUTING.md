# Contributing

Thank you for your interest. SignalDock is a portfolio project with one maintainer. Replies can be slow.

Report a security problem as described in [SECURITY.md](SECURITY.md), not in a public issue.

## Set up

Run once per clone:

```sh
git config core.hooksPath .githooks
scripts/setup-toolchain.sh     # JDK 21, oasdiff, OpenAPI generator -> .tools/
```

You also need Docker with Compose, Python 3, Node (see [TOOLCHAIN.md](TOOLCHAIN.md)), and an Android SDK with platform `android-36` (`ANDROID_HOME`). The [README](README.md#reproduce-from-a-clean-clone) lists the run commands.

## What the hooks run

- `pre-commit` runs `scripts/check-repo-rules.sh` (static checks: file modes, issue status lines, README sync, design index).
- `pre-push` runs `scripts/verify-contract.sh` as well when code changed.

## Design first

Before you edit files in an area, read the sections that [docs/design-index.md](docs/design-index.md) lists for it. Build what the design sources say. If you must make a new decision, record it in an ADR in `docs/adr/` in the same pull request and name it in the description.

A change of the SDK public API also updates the API dump, the integration guide (both languages), and `CHANGELOG.md`. See [docs/versioning.md](docs/versioning.md).

## Pull requests

- One purpose for each pull request.
- Fill in the pull request template: what changed, which design sections it follows, and the exact verification commands and results. Do not mark a step verified without a run. Keep the description under 3,500 characters.
- Before you open a pull request, run `coderabbit review --base main --deep --agent` if you have it, and fix the findings. The pull request review then confirms the work.
- A pull request is ready to merge when no Critical or Major finding is open and all checks pass.
- The `Co-Authored-By` commit trailer that maintainer agents use is not required for outside contributors.

Write English in short, clear sentences. The repository follows the ASD-STE100 principle of simple, clear language.
