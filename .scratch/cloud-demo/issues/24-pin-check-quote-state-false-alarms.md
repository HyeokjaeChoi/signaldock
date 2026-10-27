# 24. Pin check: quote and bracket state in `wfb` gives false alarms

Status: ready-for-agent
Type: task
Blocked by: None

## Scope and completion criteria

Minor findings from the PR 235 reviews (Qodo, rounds 3 and 4; Claude round 5 question), recorded under AGENTS.md rule 7.

`wfb` in `scripts/check-repo-rules.sh` stops skipping block scalars while it thinks a quote or flow bracket is open. The count is per character, not a YAML parse. These inputs leave the state wrong, so a later `run: |` body with `echo uses:` fails the pin check. All fail closed (false alarm only, no unpinned action passes):

- `name: '[note'`: a bracket inside single-quoted text is counted.
- `run: echo 5" pipe`: a `"` inside a plain scalar flips the parity.
- A multi-line single-quoted value with a doubled `''` escape.

1. Decide: accept the false alarm and write it in the comment, or track quoted scalars better (one state machine over the whole file).
2. Do not open a fail-open path. The bad samples of issues 11 and 17 and of PR 235 reviews must still fail.

Verification:
- `bash scripts/check-repo-rules.sh; echo "exit=$?"` on the repo (exit 0), and on each sample above followed by a `run: |` step with `echo uses:` (as decided).
