# 17. Pin check: reject a YAML explicit `?` key named `uses`

Status: resolved
Type: task
Blocked by: None
Execution: Done (PR #235, 2026-10-27 UTC)

## Scope and completion criteria

Minor finding from the PR 228 Claude review (round 2), recorded under AGENTS.md rule 7.

The pin check in `scripts/check-repo-rules.sh` reads the `uses` key in its plain form. YAML also allows an explicit key. A step written as `- ? uses` with `: actions/checkout@v4` on the next line is valid YAML, but it passes unpinned. No workflow uses this layout today.

1. Fail on an explicit `?` key named `uses`. Or fail on any `?` explicit key in workflow and action files. The message tells the author to write `uses: <action>@<sha>` on one line.
2. A bad sample that fails (show the sample and the output).
3. The clean repo exits 0.

Verification:
- `bash scripts/check-repo-rules.sh; echo "exit=$?"` on the repo (exit 0) and on the bad sample (exit non-zero).
