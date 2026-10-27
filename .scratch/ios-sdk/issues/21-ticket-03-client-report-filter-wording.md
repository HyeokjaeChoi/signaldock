# 21. Ticket 03: platform filter wording for client report counts

Status: ready-for-agent
Type: task
Blocked by: None

## Scope and completion criteria

Found in the Claude review of PR #237 (Minor).

Ticket 03 item 2 says the platform filter applies "like `installationId`". That is wrong for `/v1/client-report-counts`: the Dashboard does not send `installationId` there (ADR-0036 decision 9), but ADR-0046 decision 6 requires `sdkPlatform` there.

1. Change `.scratch/ios-sdk/issues/03-dashboard-platform.md` item 2: the filter goes to the event list, every event count view, and the client report counts. Remove the comparison with `installationId`.
2. Add to item 5 a test that the client report counts request has `sdkPlatform` when a platform is selected.

Verification:
- `bash scripts/check-repo-rules.sh`.
