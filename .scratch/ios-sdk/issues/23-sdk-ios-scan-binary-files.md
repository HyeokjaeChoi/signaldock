# 23. Repo-rule scan under `sdk-ios` crashes on a binary file

Status: ready-for-agent
Type: task
Blocked by: None

## Scope and completion criteria

Minor finding from the PR #247 Claude review, recorded under AGENTS.md rule 7.

The multi-line scan in `scripts/check-repo-rules.sh` (the `.package(` and `privacy: .public` checks) reads every indexed file under `sdk-ios` as UTF-8 text. A binary file (for example a PNG asset or a binary plist) raises `UnicodeDecodeError`, and the check fails with a traceback that names no rule. The old `git grep` skipped binary files.

1. Scan only the file types the rules target (`*.swift` for both checks; `Package*.swift` for the manifest check), or skip files that do not decode.
2. Bad sample: stage a binary file under `sdk-ios`; the check must not crash, and a planted violation in a Swift file must still fail.

Verification:
- `bash scripts/check-repo-rules.sh; echo "exit=$?"` on the clean repo (0), with the binary sample (0), and with the binary sample plus a planted violation (non-zero).
