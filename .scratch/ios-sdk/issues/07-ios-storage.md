# 07. iOS storage: installation ID and SQLite queue

Status: ready-for-agent
Type: task
Blocked by: 05, 20

## Scope and completion criteria

Spec D6, D7, D9. ADR-0001, ADR-0003, ADR-0017, ADR-0025, ADR-0047.

1. Installation ID: UUIDv4 file in Application Support `signaldock/`, excluded from backup; kept across restarts; a malformed file is handled as on Android (ADR-0001, ADR-0017).
2. Queue on system SQLite3 behind an internal wrapper, owned by an actor with a custom `SerialExecutor` on a dedicated serial queue. Same tables, limits, and transaction boundaries as the Android Room queue (ADR-0003); ACK application in one transaction.
3. SQL migrations as versioned files; `scripts/check-repo-rules.sh` freezes released migrations (iOS form of ADR-0025, `AGENTS.md` row).
4. Storage failure gives the Android crash-safety results (ADR-0017): no throw into the host, `storageAvailable = false`.
5. Executor: the iOS 15 form of ADR-0047 D9 (`enqueue(_: UnownedJob)`). Run the storage tests on an iOS 15 Simulator runtime (or the oldest the runner has, spec section 5) and record the runtime; this closes the ADR-0047 D9 gap.
6. Tests: deterministic, injected clock; concurrent `track` and ACK from many tasks under TSan; corrupt file and corrupt database cases; limit eviction rules.

Verification:
- `xcodebuild test` under TSan (count in the PR); repo-rules check output.
