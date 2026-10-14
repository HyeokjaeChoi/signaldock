# 34. SDK integration guide and public API documentation

Status: ready-for-agent
Type: task
Blocked by: None
Execution: Not started

## Scope and completion criteria

An SDK user (an app developer) needs one document to integrate SignalDock, and the public API needs reference comments. Today the behavior is spread across the spec, the architecture document, and the ADRs, which are written for design review, not for integration.

1. `docs/integration-guide.md` (and `docs/integration-guide.ko.md`): installation from the Maven repository ZIP (issue 06), initialization, `track()` and event naming rules, `TrackResult` handling, offline and retry behavior, queue limits, privacy notes (installation-scoped identity, ADR-0001), versioning and the contract version, and troubleshooting (the error codes the SDK can report). Every statement cites the spec or ADR section it comes from; the guide adds no new behavior.
2. KDoc on every public declaration of the SDK (the public API surface), consistent with the guide. If the build has a public API check (for example a binary compatibility dump), keep it passing.
3. Link the guide from both READMEs.

Verification:
- `cd sdk && ./gradlew test` and the build of the publication module; `scripts/verify-contract.sh`; `scripts/check-repo-rules.sh`.
- The code samples in the guide compile: either in a small test source set or by copying them from the demo or consumer app with a path reference.
