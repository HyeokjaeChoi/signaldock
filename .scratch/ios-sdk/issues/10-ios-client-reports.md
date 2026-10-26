# 10. iOS client reports

Status: ready-for-agent
Type: task
Blocked by: 03, 09

## Scope and completion criteria

Spec D1. ADR-0036.

1. Drop counts by reason, the same reasons and send rules as Android, with `sdkPlatform: "ios"`.
2. Tests: each reason counted; report sent and cleared on success; kept on failure.

Verification:
- `xcodebuild test`; Compose run: the Dashboard shows the dropped count with platform `ios`.
