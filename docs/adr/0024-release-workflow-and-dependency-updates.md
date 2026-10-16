---
status: accepted
---

# Release workflow and dependency updates (issue 41)

Spec Q149 made the first release by hand (`scripts/release.sh`, then `gh release create`). This ADR records how a tag now makes the release, and how Dependabot proposes updates. It adds no product behavior. The user can change these choices.

## Decisions

1. **A tag push builds the release (`.github/workflows/release.yml`).** The trigger is a push of a tag `v*.*.*`. The workflow never runs for `v0.1.0`: that tag exists and its release was made by hand, and a workflow runs only for a new tag push. Do not delete and re-push `v0.1.0`.
2. **Verify tag step.** It fails when the tag is not `vMAJOR.MINOR.PATCH` (pre-release tags such as `v1.0.0-rc1` match the glob but are rejected; there is no pre-release flow yet), when the version differs from `sdkVersion`, when the tagged commit is not on `origin/main`, when `docs/releases/<tag>.md` is missing (release notes are required), or when a release for the tag already exists. The `sdkVersion` comparison is `scripts/release.sh <version> --check`, a new flag on the existing script, so the workflow and a local run share one check and the check runs before any build.
3. **Build, then publish, in two jobs.** `build` has `contents: read`, runs `scripts/setup-toolchain.sh` and `scripts/release.sh <version>` (the same verification as CI: Maven ZIP, Kotlin metadata check, R8 consumer build, SHA-256 file), and uploads `dist/` as an artifact. `publish` is the only job with `contents: write`; it runs no project code, only `gh release create "$TAG" dist/*.zip dist/*.sha256 --verify-tag --notes-file ...`. `gh release create` fails when the release exists, so the workflow never overwrites one. Reason: a build that runs Gradle plugins and dependencies should not hold a write token.
4. **Pinned actions.** Third-party actions are pinned to the commit SHA of a released tag, with the tag in a comment, as the existing workflow does: `actions/checkout` v6.0.3 (same SHA as `repo-rules.yml`), `actions/upload-artifact` v7.0.2, `actions/download-artifact` v8.0.2. A tag can be moved; a SHA cannot. The release is created with `gh`, which is on the runner, so no release action is added. The tag name is passed through `env`, never put into a shell line by the expression syntax.
5. **Dependabot (`.github/dependabot.yml`).** Weekly, with small open-PR limits. Ecosystems: `gradle` for `/sdk`, `/server`, `/compatibility/consumer-android`, `/contract-check` (the four Gradle builds); `npm` for `/dashboard` and `/perf/report`; `github-actions`; `docker` for the `/server` and `/dashboard` Dockerfiles; `docker-compose` for `/`. Groups follow the blast radius: for `/sdk`, one group for what a consumer resolves from the POM (Kotlin, kotlinx, Room, WorkManager, Retrofit and OkHttp) and one for build and test tools; npm splits `development` from `production`; each other ecosystem has one group. The SDK runtime group is separate so a change that reaches consumers gets its own PR, its own review, and a CHANGELOG line.
6. **Known limit: the version catalog.** Dependabot reads a Gradle version catalog only at `gradle/libs.versions.toml` of the update directory (dependabot-core `file_fetcher.rb`: the lookup runs for the root directory only and does not follow `from(files("../gradle/libs.versions.toml"))`). The SignalDock catalog is at the repo root, and the repo root has no Gradle build, so Dependabot cannot see any version in it. The build files contain no literal versions, so the `gradle` entries find nothing today. They stay so that a literal added later is covered. Catalog versions (including the SDK runtime ones) are bumped by hand; the pins carry reasons in comments (for example OkHttp 5.4.0, Kotlin metadata 2.2, ADR-0006, ADR-0009), which a bot would not respect. Alternatives not taken: a root `settings.gradle.kts` that includes the four builds (changes how every build resolves), and a copy of the catalog in each directory (drift). Both are larger than this issue.

## Not verified here

- The workflow ran on no tag. `actionlint` was not installed; the YAML parses, and the tag logic ran in a shell (see issue 41). The Dependabot file has not been parsed by GitHub; its result shows in the repository's Insights, Dependency graph, Dependabot.
- Pushing a tag from a protected-tag rule, or a repository setting that blocks `GITHUB_TOKEN` from writing releases ("Workflow permissions"), would fail the `publish` job.

## Amendment (2026-10-16 UTC)

The first Dependabot run (PRs #115 to #122) showed two problems.

1. **Runtime majors.** It proposed PostgreSQL 17 to 18, Node 24 to 26, Eclipse Temurin 21 to 24 and `@types/node` 24 to 26. PostgreSQL 18 cannot read a PostgreSQL 17 data directory without a dump and restore, and the JDK and Node lines are pinned in `TOOLCHAIN.md`. Dependabot now ignores semver-major updates for Docker images, Compose images and `@types/node`. A runtime major is a manual decision with its own PR.
2. **PR description length.** The `length` job (3,500 characters) failed on every Dependabot PR, because Dependabot writes release notes into the body. The job now skips PRs opened by `dependabot[bot]`. The limit exists for CodeRabbit and for human-written design records; release notes are neither.

