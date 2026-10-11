# 13. Server layered Gradle modules

Status: ready-for-agent
Type: task
Blocked by: 11
Execution: Implemented on branch impl/13-server-layered-modules (local commit, not pushed, no PR)

## Scope and completion criteria

Split `server/` into `:app`, `:ingestion`, and `:persistence` as ADR-0005, section "Server: layered Gradle modules", defines. `:ingestion` must not compile against Ktor, jOOQ, Flyway, or the generated DTOs. No behavior change.

Done when server tests with `verifyJooq` and `scripts/verify-contract.sh` pass, with commands and results recorded here.

## Comments

2026-10-11: Created with ADR-0005.

2026-10-11: Implemented on `impl/13-server-layered-modules` (from main 3d38388). No behavior change intended.

Module map (old -> new), all under `server/`:
- `build.gradle.kts` -> root only declares plugin versions; `app/`, `ingestion/`, `persistence/` each have their own `build.gradle.kts`. `settings.gradle.kts` adds `include(":app", ":ingestion", ":persistence")`.
- `src/main/.../Application.kt` -> `:app` `Application.kt` (routes, headers, HTTP status via `httpStatusOf(code)`, `main`).
- `src/main/.../IngestValidation.kt` -> split. Raw JSON scanner, UTF-8 decode, DTO decode (`EventIngest`) and DTO-to-domain mapping: `:app` `RequestParsing.kt`. Rules (size and count limits, contract version, UUIDv4, text, `occurredAt`, numbers, property checks, event ID conflict groups) and domain types (`RequestFailure`, `StoredEvent`, `ValidatedBatch`, `ValidatedItem`, `PropertyValue`, `sameContent`): `:ingestion` `IngestRules.kt`, `Domain.kt`. `RequestFailure` no longer carries an HTTP status.
- `src/main/.../Ingestion.kt` -> split. Receipt and duplicate decisions (`planBatch`, `EventGroup.receipts`, `ItemReceipt`): `:ingestion` `ReceiptPlan.kt`. SQL and JSON storage format: `:persistence` `EventStore.kt`. `ItemAck` DTO mapping: `:app` `Ingestion.kt`.
- `src/main/.../Database.kt` -> `:persistence` `Database.kt` (package `local.signaldock.persistence`).
- `src/main/resources/db/migration` -> `persistence/src/main/resources/db/migration`.
- `src/codegen/.../JooqCodegen.java` -> `persistence/src/codegen/...`; `generateJooq` and `verifyJooq` now live in `:persistence`.
- `generated/jooq` -> `persistence/generated/jooq` (content unchanged; generated package stays `local.signaldock.server.jooq`).
- `../generated/kotlin` DTOs compile only in `:app` (still `internal`).
- `IngestValidationTest` -> `:app` (it tests the parser). `IngestionIntegrationTest` -> `:app`, minus two pure database tests. Those two (admission overload, statement timeout) -> `:persistence` `DatabaseTest`.
- New tests: `:ingestion` `IngestRulesTest` (4), `:persistence` `DatabaseTest` JSON round trip (1).

Commands and results (JDK 21.0.12.1+1 from `scripts/setup-toolchain.sh`):
- `(cd server && ./gradlew test verifyJooq --no-daemon --rerun-tasks)`: BUILD SUCCESSFUL. Tests: `:app` 19 (8 parser + 11 integration), `:ingestion` 4, `:persistence` 3. Before the split: 21 tests (8 + 13). All 21 run again (19 in `:app`, 2 moved to `:persistence`), plus 5 new.
- `(cd server && ./gradlew :ingestion:dependencies --configuration compileClasspath --no-daemon)`: only `org.jetbrains.kotlin:kotlin-stdlib:2.4.21` and `org.jetbrains:annotations:13.0`. `runtimeClasspath` shows the same. No Ktor, jOOQ, Flyway, JDBC, or serialization.
- `scripts/verify-contract.sh`: exit 0, "ALL CONTRACT CHECKS PASSED".
