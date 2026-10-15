# 42. Community health files and version policy

Status: claimed
Type: task
Blocked by: None
Execution: Implemented on branch impl/community-health, PR pending

## Scope and completion criteria

Docs and templates only. No product code changes. Facts come from the repository; no invented support promise or contact address.

1. `SECURITY.md`: supported versions (0.1.x), private reporting through GitHub private vulnerability reporting, scope, and known non-goals (local-only stack, the collection key is not a secret, spec section 5 and Q128).
2. `CONTRIBUTING.md`: setup, what the hooks run, design-first rule, pull request rules from `AGENTS.md`. The commit trailer is not required for outside contributors.
3. `.github/ISSUE_TEMPLATE/` (bug report, feature request, `config.yml` that points security reports to `SECURITY.md`) and `.github/PULL_REQUEST_TEMPLATE.md`.
4. `docs/versioning.md`: SemVer for the SDK, what is a breaking change (API dump, spec 3 behavior, persisted format, requirements), a deprecation rule modeled on Sentry, contract version versus SDK version (ADR-0012), and an honest 0.x support window.
5. `README.md` and `README.ko.md`: a "Supported versions" table and links to the new files. `docs/design-index.md` and `CHANGELOG.md` `[Unreleased]`.

Verification:
- `scripts/check-repo-rules.sh`; `coderabbit review --base main --deep --agent`.

## Comments
