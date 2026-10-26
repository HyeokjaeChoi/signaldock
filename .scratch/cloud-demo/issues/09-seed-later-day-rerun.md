# 09. Seed: stop a later-day rerun before it sends many batches

Status: claimed
Type: task
Blocked by: None

## Scope and completion criteria

Minor finding from the PR 210 review (thread 4370153563), recorded under AGENTS.md rule 7.

On a later-day rerun without a reset, today's session count differs by time of day. Only the shared RNG prefix reuses event IDs. `batches()` orders installations by earliest event, so many batches can be accepted before a `CONFLICT` stops the run. The `scripts/demo-seed.py` docstring says `CONFLICT` comes on the first data batch. The run still exits 1, and the D3 reset clears the data.

1. Either send the batches that hold today's (reused-ID) sessions first, or add a pre-check that stops before any data batch.
2. Fix the docstring to match.
3. A test with the fake server.

Verification:
- `python3 -I -m unittest discover -s scripts -p 'test_*.py'`
