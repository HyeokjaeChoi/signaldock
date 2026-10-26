# 11. Pin check: reject a `uses` key whose value is on the next line

Status: resolved
Type: task
Blocked by: None
Execution: Done (PR #228, 2026-10-26 UTC)

## Scope and completion criteria

Minor finding from the PR 216 review (thread 4370390020), recorded under AGENTS.md rule 7.

The pin check in `scripts/check-repo-rules.sh` reads `uses` values only on the same line as the key. A step written as `- uses:` with the value `actions/checkout@v4` on the next line is valid YAML, but it passes unpinned. No workflow uses this layout today.

1. Fail on a `uses` key that has no value on its line. The message tells the author to write the value on the same line.
2. A bad sample that fails (show the sample and the output).
3. The clean repo exits 0.

Verification:
- `bash scripts/check-repo-rules.sh; echo "exit=$?"` on the repo (exit 0) and on the bad sample (exit non-zero).
