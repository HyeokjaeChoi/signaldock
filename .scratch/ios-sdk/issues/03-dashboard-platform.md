# 03. Dashboard: platform display and filter

Status: ready-for-agent
Type: task
Blocked by: 01, 02

## Scope and completion criteria

Spec D5. ADR-0007, ADR-0015 (languages), ADR-0016 (style).

1. Event list column and detail field for the platform.
2. Platform filter in the shared filter set; it applies to the list and every count view, like `installationId`.
3. Client report view shows platform with version.
4. UI text in every supported language (ADR-0015). No raw HTML sink (`scripts/check-repo-rules.sh`).
5. Tests: filter sent as `sdkPlatform`, absent when "all", display of both values.

Verification:
- Dashboard test and build commands (counts in the PR).
- Compose: an `ios` event and an `android` event show the right value; the filter narrows list and counts.
