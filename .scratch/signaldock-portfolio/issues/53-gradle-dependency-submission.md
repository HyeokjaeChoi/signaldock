# 53. Submit Gradle dependencies to the GitHub dependency graph

Status: claimed
Type: task
Blocked by: None
Execution: Branch `ci/gradle-dependency-submission`; PR pending

## Scope and completion criteria

Spec Q161 (in PR #144): security fixes use Renovate and GitHub Dependabot alerts. GitHub does not parse Gradle builds, so Gradle dependencies (with transitive ones) reach the dependency graph only through the Dependency Submission API. No new ADR: this follows Q161.

1. `.github/workflows/dependency-submission.yml` runs `gradle/actions/dependency-submission` for `sdk`, `server`, `contract-check`, and `compatibility/consumer-android`, on push to `main` and `workflow_dispatch` only. It never runs on a PR.
2. Permissions: none at workflow level; `contents: write` for the job only. Every action is pinned by commit SHA.
3. Default configurations are kept: all resolved configurations (runtime, test, and build tools) are submitted. The SDK published runtime classpath matters most; the rest is useful for build and test tool alerts.
4. Cache: `cache-provider: basic` (plain `actions/cache`, no proprietary service). The graph is not saved as a workflow artifact.
5. Docs: supply-chain row in `docs/engineering/threat-model.md`, pointer in `docs/design-index.md`.

Gap: the workflow has not run. After merge, run `workflow_dispatch` once and check Insights, Dependency graph.

## Comments
