# SignalDock review rules (repository-wide)

Review against these project invariants, not generic best practices. When a change contradicts an invariant, flag it. When unsure whether a generic concern applies here, check the spec and ADRs first — several "obvious" improvements are deliberately rejected in this project.

Area rules are in `.github/instructions/*.instructions.md`.

### Do not suggest
Shared Kotlin DTO artifacts, a DI framework in the SDK, Redis for idempotency, aggregate tables or counters, iOS or Flutter SDKs, Maven Central publication, automatic events or user identify, or trimming and normalizing names and keys.
