# 08. Seed: treat 503 TEMPORARILY_UNAVAILABLE as an uncertain outcome

Status: resolved
Type: task
Blocked by: None
Execution: Done (PR #229, 2026-10-26 UTC)

## Scope and completion criteria

Minor finding from the PR 210 review (thread 4370143620), recorded under AGENTS.md rule 7.

`scripts/demo-seed.py` treats only 503 `REQUEST_TIMEOUT` as an uncertain storage outcome. The server also maps `DatabaseUnavailable("COMMIT_OUTCOME_UNCERTAIN")` (a commit that may have succeeded) to 503 `TEMPORARILY_UNAVAILABLE` (`docs/engineering/failure-modes.md` row 11, ADR-0036 item 7). After such a 503:
- an event batch retry gets `duplicate`, and the script exits 1 with a false "already stored" or "already holds a seed" message;
- a client report retry can count twice.

The run fails safe (exit 1, or a small count error); the D3 daily reset seeds again the next day.

1. Treat every 503 on an event batch as uncertain: accept `duplicate` after any retried 503.
2. For `/v1/client-reports`, do not retry any 503; fail with the reset message.
3. Keep the sentinel guard: a first-attempt `duplicate` or `CONFLICT` still means "already seeded".
4. Tests with the fake server for each case.

Verification:
- `python3 -I -m unittest discover -s scripts -p 'test_*.py'`
