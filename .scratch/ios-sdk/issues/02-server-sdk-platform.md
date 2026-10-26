# 02. Server: store, default, kill switch, filters

Status: ready-for-agent
Type: task
Blocked by: 01

## Scope and completion criteria

Spec D3, D4, D5. ADR-0046, ADR-0037, ADR-0007.

1. Flyway `V4`: `sdk_platform TEXT COLLATE "C" NOT NULL` on `events` and `client_reports`, old rows `android`, a check constraint for the two values. V1 to V3 do not change.
2. `RequestParsing` accepts `sdkPlatform` in the event field set and the client report root. Ingest stores the sent value, or `android` when the field is missing. An unknown value is a per-item rejection, like other invalid fields (ADR-0027).
3. `SIGNALDOCK_BLOCKED_SDK_VERSIONS` items are `platform:version`; an item with no prefix is `android:<item>`. A malformed item fails the start, like the other variables. `CollectionControl` matches the pair.
4. Query: `sdkPlatform` in list and detail responses; the filter on the five endpoints; client report counts group by (platform, version). Strict parameter parsing (`INVALID_PARAMETER` for other values).
5. Tests with real PostgreSQL (Testcontainers): default on a missing field, both values stored, kill-switch pairs and the no-prefix rule, each filter, the grouping, `EXPLAIN` still has no sequential scan on the ADR-0007 cases.

Verification:
- `server` test task (command and count in the PR).
- `scripts/verify-contract.sh`, `bash scripts/check-repo-rules.sh`.
- Compose smoke: one event with `ios`, one without the field; both stored as expected.
