# 06. Swift wire models from the contract

Status: claimed
Type: task
Blocked by: 01, 04, 05

## Scope and completion criteria

Spec D10. ADR-0002, ADR-0047.

1. `scripts/codegen.sh`: openapi-generator `swift6` (same pinned jar), models only, `internal`, into `sdk-ios/Sources/SignalDock/Generated` (not `generated/swift`: ADR-0047 amendment, the ZIP has no `generated/`); remove supporting files like the Kotlin step. Drift check covers the new folder.
2. The models compile in `sdk-ios` with Foundation only, and `PropertyValue` decodes and encodes string, number, and boolean by value as `fixtures/roundtrip` and `fixtures/sdk-wire/android/` say (number literals kept on decode; spelling on encode may differ, ADR-0047 D10).
3. If 2 fails or the output needs a runtime library: switch to hand-written `Codable` types under `sdk-ios`, checked by the same fixtures, and record the switch in an ADR (AGENTS.md rule 2).

Verification:
- `scripts/verify-contract.sh`; iOS fixture tests (count in the PR).
