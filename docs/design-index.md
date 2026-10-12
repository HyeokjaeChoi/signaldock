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
- ADR-0003 (`docs/adr/0003-sdk-storage-decisions.md`)
- ADR-0005 (`docs/adr/0005-module-structure.md`), section "SDK: one publication module, feature packages"
- ADR-0006 (`docs/adr/0006-sdk-delivery-decisions.md`)
- ADR-0011 (`docs/adr/0011-sdk-device-test-harness.md`), for `sdk/sdk-android/src/androidTest` and `scripts/device-durability.sh`

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

Modules: `server/app`, `server/ingestion`, `server/query`, `server/persistence`. The tracked jOOQ output is `server/persistence/generated/jooq`.

Architecture (`architecture.md`):
- Collection, delivery, and ACK
- Lost ACKs and event deduplication
- DB time budgets and queries
- Contracts, generated sources, and JSON

ADRs:
- ADR-0002
- ADR-0004 (`docs/adr/0004-server-runtime-and-receipt-boundaries.md`)
- ADR-0005, section "Server: layered Gradle modules"
- ADR-0007 (`docs/adr/0007-dashboard-query-api.md`), for `server/query` and the query routes

Spec (`.scratch/signaldock-portfolio/spec.md`):
- 2. Agreed stack and boundaries
- 5. Server receipt and duplicate handling
- 6. User screens and distribution, Dashboard (for `server/query`)
- 8, B. Errors, ACKs, and data representation
- 11. Delegated performance measurement decisions
- 12. Dashboard role confirmation and default delegation

Also read `.scratch/signaldock-portfolio/dashboard-verification-defaults.md` before you edit `server/query` or the query routes.

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

## `demo/` (Android demo app, issue 05)

Architecture (`architecture.md`):
- Components and responsibilities

ADRs:
- ADR-0005, section "Demo app: feature modules and unidirectional data flow"

Spec (`.scratch/signaldock-portfolio/spec.md`):
- 6. User screens and distribution, Demo and diagnostics

## `dashboard/`

Architecture (`architecture.md`):
- Components and responsibilities
- DB time budgets and queries

ADRs:
- ADR-0002
- ADR-0007 (`docs/adr/0007-dashboard-query-api.md`)

Spec (`.scratch/signaldock-portfolio/spec.md`):
- 6. User screens and distribution, Dashboard
- 8, B. Errors, ACKs, and data representation
- 11. Delegated performance measurement decisions
- 12. Dashboard role confirmation and default delegation

Also read `.scratch/signaldock-portfolio/dashboard-verification-defaults.md`.

## `perf/` (server load measurement harness, issue 23)

Architecture (`architecture.md`):
- DB time budgets and queries

ADRs:
- ADR-0004
- ADR-0007
- ADR-0008
- ADR-0012
- ADR-0013 (`docs/adr/0013-server-load-measurement-harness.md`)

Spec (`.scratch/signaldock-portfolio/spec.md`):
- 7. Completion criteria and verification plan
- 11. Delegated performance measurement decisions
- Q124 and Q125 (normal-operation waits stay separate from benchmark values)

Also read `.scratch/signaldock-portfolio/performance-plan.md`; it is authoritative for the fixed conditions, load, and acceptance.

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
- Several SDK publication modules or a fused AAR: ADR-0005, section "SDK: one publication module, feature packages".
- DI framework in the SDK: spec section "8. Additional implementation policies and individual agreements", "C. Small modules and local execution" (Q87).
- Redis for idempotency: architecture "Lost ACKs and event deduplication"; spec Q69.
- Aggregate tables or counters: architecture "DB time budgets and queries"; spec Q70.
- iOS or Flutter SDKs, Maven Central, automatic events, identify: spec "1. Goal and scope".
- Trimming or normalizing names and keys: spec Q64 and Q84.
