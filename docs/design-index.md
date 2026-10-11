# Design index

Read the listed sections before you edit or review files in an area.
Refer to sections by heading text.

## `sdk/`

Architecture (`architecture.md`):
- Components and responsibilities
- Collection, delivery, and ACK
- Lost ACKs and event deduplication
- SDK lifecycle and cancellation
- Retry and transport boundaries
- Installation lifecycle and durability
- Contracts, generated sources, and JSON

ADRs:
- ADR-0001 (`docs/adr/0001-installation-scoped-identity.md`)
- ADR-0002 (`docs/adr/0002-openapi-json-contract.md`)

Spec (`.scratch/signaldock-portfolio/spec.md`):
- 1. Goal and scope
- 3. SDK public behavior
- 4. Local storage and delivery
- 6. User screens and distribution, Demo and diagnostics
- 8. Additional implementation policies and individual agreements, A. Scheduling and failure recovery
- 8, B. Errors, ACKs, and data representation
- 8, C. Small modules and local execution
- 12. Dashboard role confirmation and default delegation

## `server/`

Architecture (`architecture.md`):
- Collection, delivery, and ACK
- Lost ACKs and event deduplication
- DB time budgets and queries
- Contracts, generated sources, and JSON

ADRs:
- ADR-0002

Spec (`.scratch/signaldock-portfolio/spec.md`):
- 2. Agreed stack and boundaries
- 5. Server receipt and duplicate handling
- 8, B. Errors, ACKs, and data representation
- 11. Delegated performance measurement decisions
- 12. Dashboard role confirmation and default delegation

## `openapi/`, `fixtures/`, `generated/`, `contract-check/`

Architecture (`architecture.md`):
- Contracts, generated sources, and JSON

ADRs:
- ADR-0002

Spec (`.scratch/signaldock-portfolio/spec.md`):
- 7. Completion criteria and verification plan (contract rows)
- 8, B. Errors, ACKs, and data representation
- 8, C. Small modules and local execution
- 9. Technical conditions to check on entry into implementation

## `dashboard/`

Architecture (`architecture.md`):
- Components and responsibilities
- DB time budgets and queries

ADRs:
- ADR-0002

Spec (`.scratch/signaldock-portfolio/spec.md`):
- 6. User screens and distribution, Dashboard
- 8, B. Errors, ACKs, and data representation
- 11. Delegated performance measurement decisions
- 12. Dashboard role confirmation and default delegation

Also read `.scratch/signaldock-portfolio/dashboard-verification-defaults.md`.

## `scripts/`, `.githooks/`, `.github/workflows/`, `gradle/`

Architecture (`architecture.md`):
- Independent distribution and verification

ADRs:
- ADR-0002

Spec (`.scratch/signaldock-portfolio/spec.md`):
- 7. Completion criteria and verification plan
- 8, C. Small modules and local execution
- 9. Technical conditions to check on entry into implementation
- 10. Proposed implementation order

Also read `TOOLCHAIN.md`.

## Rejected alternatives

This section only points to where each decision is recorded.
Do not suggest these alternatives.

- Shared Kotlin DTO artifact: ADR-0002.
- DI framework in the SDK: spec section "8. Additional implementation policies and individual agreements", "C. Small modules and local execution" (Q87).
- Redis for idempotency: architecture "Lost ACKs and event deduplication"; spec Q69.
- Aggregate tables or counters: architecture "DB time budgets and queries"; spec Q70.
- iOS or Flutter SDKs, Maven Central, automatic events, identify: spec "1. Goal and scope".
- Trimming or normalizing names and keys: spec Q64 and Q84.
