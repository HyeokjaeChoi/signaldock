# 24. Pin check: the visible-`uses` rule in `wfb` misses a key with an anchor or tag

Status: ready-for-agent
Type: task
Blocked by: None

## Scope and completion criteria

Minor finding from the PR 235 Claude review (round 6), recorded under AGENTS.md rule 7.

`wfb` in `scripts/check-repo-rules.sh` skips block-scalar bodies, but keeps a skipped line visible when `uses` or `?` starts a token on it. The token pattern does not allow an anchor or tag first. In a false skip region (a `k: |` text inside a multi-line quoted scalar in a flow collection), the line `", &x uses: evil/x@v1 }` is dropped and the pin check passes. Before PR 235 the check caught it. This needs a valid but unusual workflow.

1. Allow `([&!][^ ]*[[:space:]]+)*` before the key (and before `?`) in the visible-line pattern. Or stop skipping in a file that has a multi-line quoted or flow scalar.
2. Bad sample: the case above, followed by a check that it fails.
3. The `run: |` + `echo uses:` sample still passes, and the clean repo exits 0.

Verification:
- `bash scripts/check-repo-rules.sh; echo "exit=$?"` on the repo (exit 0), on the bad sample (non-zero), and on the `run: |` sample (exit 0).
