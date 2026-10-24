# Contributing

Thank you for your interest. SignalDock is an open-source project with one maintainer. Replies can be slow.

Report a security problem as described in [SECURITY.md](SECURITY.md), not in a public issue.

## Set up

Run once per clone:

```sh
git config core.hooksPath .githooks
scripts/setup-toolchain.sh     # JDK 21, oasdiff, OpenAPI generator -> .tools/
```

You also need Docker with Compose, Python 3, Node (see [TOOLCHAIN.md](TOOLCHAIN.md)), and an Android SDK with platform `android-36` (`ANDROID_HOME`). The demo app and `scripts/update-gradle-verification.sh` also need platform `android-37`. The [README](README.md#reproduce-from-a-clean-clone) lists the run commands.

## What the hooks run

- `pre-commit` runs `scripts/check-repo-rules.sh` (static checks: file modes, issue status lines, README sync, design index, no raw HTML sinks in the Dashboard and report apps, frozen Room schema files, `SDK_VERSION` equal to `sdkVersion`).
- `pre-push` always runs `scripts/check-repo-rules.sh`, so it can reject a docs-only push too. When code changed, it also runs `scripts/verify-contract.sh`.

## Change a Gradle dependency

Each Gradle build checks every dependency and plugin file against `gradle/verification-metadata.xml` ([ADR-0038](docs/adr/0038-gradle-dependency-verification.md)). When you change `gradle/libs.versions.toml`, a plugin, or a dependency, the build fails with "Dependency verification failed" until you run:

```sh
scripts/update-gradle-verification.sh
```

Commit the changed `verification-metadata.xml` files. Read the diff: a new entry must belong to the dependency that you changed. A Renovate Gradle PR needs the same step. Check out its branch, run the script, and push once.

## Design first

Before you edit files in an area, read the sections that [docs/design-index.md](docs/design-index.md) lists for it. Build what the design sources say. If you must make a new decision, record it in an ADR in `docs/adr/` in the same pull request and name it in the description.

A change of the SDK public API also updates the API dump, the integration guide (both languages), and `CHANGELOG.md`. See [docs/versioning.md](docs/versioning.md).

## Pull requests

- One purpose for each pull request.
- Fill in the pull request template: what changed, which design sections it follows, and the exact verification commands and results. Do not mark a step verified without a run. Keep the description under 3,500 characters.
- Before you open a pull request, review your own diff and fix what you find; if you use Claude Code, run its `/code-review` skill (AGENTS.md "Commands"). The pull request review then confirms the work.
- A pull request is ready to merge when no Critical or Major finding is open and all checks pass. Record each open Minor or lower finding as a local issue (see [docs/agents/issue-tracker.md](docs/agents/issue-tracker.md)), link it in the pull request, and do not hold the merge for it.
- The `Co-Authored-By` commit trailer that maintainer agents use is not required for outside contributors.

Write English in short, clear sentences. The repository follows the ASD-STE100 principle of simple, clear language.
