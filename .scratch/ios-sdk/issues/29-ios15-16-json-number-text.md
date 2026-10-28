# 29. iOS 15 and 16: verify `JSONEncoder` number text

Status: ready-for-agent
Type: task
Blocked by: None

## Scope and completion criteria

Open question from the Claude review of PR 259. ADR-0047 ticket 06 did not verify the `JSONEncoder` number text on iOS 15 and 16. An older Foundation may write `%.17g`-style text (for example `0.10000000000000001`). The server rejects such text under the unchanged precision-loss rule (ADR-0027, amendment 2026-10-28).

1. Run the iOS wire and encoding tests on an iOS 15 runtime and an iOS 16 runtime. If a runtime is not available, document how to get one.
2. If the encoder writes non-shortest text, fix the SDK encoding so the server accepts the values.

Verification:
- The test commands, the runtimes, and the results are in the PR.
- `bash scripts/check-repo-rules.sh; echo "exit=$?"` is 0.
