# 59. Clear the CodeQL alerts on main

Status: resolved
Type: task
Blocked by: None
Execution: Done (merged in PR #158, 2026-10-17 00:19:50 UTC)

## Scope and completion criteria

ADR-0032 recorded 51 open CodeQL alerts on `main` (0 security). This issue clears them. One purpose: alert cleanup.

1. Fix the 14 Python alerts in `perf/` without a change in script output.
2. Add `.github/codeql/codeql-config.yml` with `paths-ignore: [generated/typescript]` and use it in `.github/workflows/codeql.yml`.
3. Amend ADR-0032 with the triage result; update `docs/engineering/threat-model.md`.
4. The 14 remaining alerts are dismissed in GitHub with reasons (done on 2026-10-16 UTC, see ADR-0032).

Verification: `python3 -m py_compile perf/*.py`; summary scripts give byte-identical output before and after; open alert count on the PR merge ref.

## Comments

- 2026-10-17 UTC: PR #158 merged 00:19:50Z. Result: 14 Python alerts in `perf/` fixed, 23 alerts ignored through `paths-ignore` in `.github/codeql/codeql-config.yml`, 14 alerts dismissed in GitHub on 2026-10-16 UTC with reasons (ADR-0032 "Triage result"). Open alerts on the PR merge ref: 0.
- Review: `coderabbit review --base main --deep --agent` gave 1 Major (`readlines` in `parse_server_log`), fixed. The PR review of the last commit had no actionable comments.
- 2026-10-17 UTC, after the merge: open code-scanning alerts on `refs/heads/main`: 0 (`gh api "repos/HyeokjaeChoi/signaldock/code-scanning/alerts?state=open&ref=refs/heads/main&per_page=100" --jq length`).
- Gap: `perf/run_one.py` and `perf/overload.py` were not run end to end (they need Docker and k6) (PR #158).
