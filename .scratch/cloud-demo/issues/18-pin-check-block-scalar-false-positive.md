# 18. Pin check: a `run: |` line that ends in `uses:` fails the empty-`uses` guard

Status: ready-for-agent
Type: task
Blocked by: None

## Scope and completion criteria

Minor finding from the PR 228 Qodo review (round 1), recorded under AGENTS.md rule 7.

The empty-`uses` guard in `scripts/check-repo-rules.sh` (issue 11) reads text lines. A line inside a `run: |` block that ends in `uses:` (for example `echo uses:`) fails the guard, but it is not a step key. The guard fails closed: no unpinned action passes. The cost is a false alarm.

1. Decide one of two ways, and write the reason:
   - Accept the false alarm and document it in the check message or a comment.
   - Track block-scalar context so that lines inside `run: |` are skipped.
2. A sample with `run: |` and `echo uses:` passes (if you track block-scalar context).
3. The bad samples of issue 11 and issue 17 still fail.

Verification:
- `bash scripts/check-repo-rules.sh; echo "exit=$?"` on the repo (exit 0), on the `run: |` sample (as decided), and on the bad samples of issue 11 and 17 (exit non-zero).
