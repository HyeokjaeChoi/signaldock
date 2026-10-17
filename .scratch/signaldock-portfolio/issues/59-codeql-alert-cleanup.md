# 59. Clear the CodeQL alerts on main

Status: claimed
Type: task
Blocked by: None
Execution: Branch `ci/codeql-alert-cleanup`; PR #158 (open)

## Scope and completion criteria

ADR-0032 recorded 51 open CodeQL alerts on `main` (0 security). This issue clears them. One purpose: alert cleanup.

1. Fix the 14 Python alerts in `perf/` without a change in script output.
2. Add `.github/codeql/codeql-config.yml` with `paths-ignore: [generated/typescript]` and use it in `.github/workflows/codeql.yml`.
3. Amend ADR-0032 with the triage result; update `docs/engineering/threat-model.md`.
4. The 14 remaining alerts are dismissed in GitHub with reasons (done on 2026-10-16 UTC, see ADR-0032).

Verification: `python3 -m py_compile perf/*.py`; summary scripts give byte-identical output before and after; open alert count on the PR merge ref.
