# 03. Dashboard: platform display and filter

Status: resolved
Type: task
Blocked by: 01, 02
Execution: Done (PR #249, 2026-10-28 UTC)

## Scope and completion criteria

Spec D5. ADR-0007, ADR-0015 (languages), ADR-0016 (style).

1. Event list column and detail field for the platform.
2. Platform filter in the shared filter set. It goes to the event list, every event count view, and the client report counts (ADR-0046 decision 6).
3. Client report view shows platform with version.
4. UI text in every supported language (ADR-0015). No raw HTML sink (`scripts/check-repo-rules.sh`).
5. Tests: filter sent as `sdkPlatform`, absent when "all", display of both values. The client report counts request has `sdkPlatform` when a platform is selected. The dropped events rows keep a unique key when platform, version, and reason differ.

Verification:
- Dashboard test and build commands (counts in the PR).
- Compose: an `ios` event and an `android` event show the right value; the filter narrows list and counts.

## Verification run 2026-10-29

In the Dashboard against a Compose stack: the platform column showed iOS and Android correctly. The iOS filter narrowed the event list, today count, by-name counts, and plan violations to the 2 iOS events. With the platform filter applied, the dropped-events table showed `iOS / 0.1.0 / queue_full / 3`.
