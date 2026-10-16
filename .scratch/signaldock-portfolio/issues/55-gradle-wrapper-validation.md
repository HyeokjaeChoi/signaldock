# 55. Validate Gradle wrapper JARs in CI

Status: resolved
Type: task
Blocked by: None
Execution: Done (merged in PR #146, 2026-10-16 16:39:15 UTC)

## Scope and completion criteria

Spec Q162 (cited from the CodeQL issue, 54): CI checks the Gradle wrapper JARs for supply-chain tampering.

1. A `gradle-wrapper` job in `.github/workflows/repo-rules.yml` runs `gradle/actions/wrapper-validation`, pinned by commit SHA with the tag in a comment (ADR-0024 item 4). It runs on `pull_request` and on `push` to `main`, with `contents: read`.
2. The action checks every `gradle-wrapper.jar` in the checkout: `sdk`, `server`, `contract-check`, `compatibility/consumer-android`.
3. `docs/engineering/threat-model.md` supply-chain row names the job.

Verification: YAML parse of `.github/workflows/repo-rules.yml`; `scripts/check-repo-rules.sh`; `coderabbit review --base main --deep --agent`; the `gradle-wrapper` job result on the PR.

## Comments

- No ADR: the change follows ADR-0024 item 4 and adds no decision of its own.
- 2026-10-16 UTC: PR #146 merged 16:39:15Z.
