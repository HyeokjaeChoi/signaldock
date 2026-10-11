# 01. Toolchain and OpenAPI round trip

Status: ready-for-agent
Type: task
Blocked by: None
Execution: Not started; prefer a low-cost model, with no restriction to a specific model

## Scope and completion criteria

Pin compatible versions, generate and compile Kotlin/TypeScript scalar and ACK DTOs, verify JSON round-trip fixtures, and check independent Gradle paths.

Preserve the applicable contracts in the [agreed spec](../spec.md) and [final review](../final-review.md). Distinguish implementation, execution, and failure evidence. Assess completion after recording the outputs and actual verification.

## Comments

2026-10-09: Created after the full Q127 agreement.

Toolchain research specified model=gpt-6.1-sol, but the runtime blocked it with `The 'gpt-6.1-sol' model is not supported when using Codex with a ChatGPT account.` A more expensive model was not substituted. The implementation worker was also asked to stop and report changes. No claim is made that compatible versions were researched or that a build succeeded.

The implementation worker reported that it only inspected documentation and the environment. It did not create or modify product files or run build/codegen/test. That worker did not receive the same model error directly. The research worker's error does not establish that all gpt-6.1-sol execution is unavailable. Both workers were terminated. The model path must be checked on the next run.

2026-10-09 user correction: The model name was an example, not a requirement to use that specific model. Prefer a low-cost model and do not treat the failure of that one model as a blocker for all implementation. Product code and build/codegen/test remain not started.

2026-10-09 implementation:

The worker reported the work as implemented. It is awaiting PR review.

### Pinned toolchain (gradle/libs.versions.toml, TOOLCHAIN.md)
- JDK 17 (Temurin 17.0.20.1), Gradle 8.14.5, Kotlin 2.4.21, kotlinx.serialization 1.11.0
- openapi-generator 7.26.0, oasdiff v1.33.0, TypeScript 7.0.2, Node 24.20.0
- AGP 8.13.2 (latest stable 8.x; 9.x skipped as breaking migration), KSP deferred (no 2.4.x release yet)
- Doc-pinned for later issues: Room 2.8.5, WorkManager 2.12.0, Retrofit 3.0.0, OkHttp 5.5.0, Compose BOM 2026.09.00, Ktor 3.6.0, jOOQ 3.21.9, Flyway 13.10.0

### Contract
- `openapi/contract-v1.yaml`: ingestion API, `SignalDock-Contract-Version` header, per-item ACK, error model. Prior-schema fixture baseline for oasdiff.
- Pinned template override `openapi/templates/oneof_class.mustache` fixes two upstream bugs: (1) oneOf number branch never matched JSON numbers (BigDecimal not flagged primitive); (2) `BigDecimal.serializer()` does not exist.

### Execution evidence
- `scripts/codegen.sh`: generates Kotlin (kotlinx_serialization) + TypeScript (typescript-fetch) models into `generated/` (tracked per Q91).
- Kotlin models compile with kotlinc 2.4.21 + serialization plugin (25 classes).
- `contract-check` round-trip tests: 5/5 pass. Fixtures cover unicode, numeric offsets (+09:00), BigDecimal precision (1.0, 1e0), null/nested rejection. Note: `-0.0` normalizes to `0.0`, consistent with Q62 (-0 == 0).
- TypeScript models compile with tsc 7.0.2 (strict); union type `boolean | number | string` verified.
- oasdiff self-diff: `{}` (no breaking changes).
- Independent builds: `sdk/`, `server/`, `contract-check/` each have own settings + wrapper 8.14.5 + shared version catalog.

### Environment limitation
Gradle daemon cannot run in this sandbox (IPv4/IPv6 localhost conflict + egress proxy). Verification used kotlinc/tsc directly with the pinned versions; `scripts/verify-contract.sh` documents the Gradle-based workflow for CI/dev machines.
