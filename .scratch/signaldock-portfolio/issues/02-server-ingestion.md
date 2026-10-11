# 02. Durable server receipt and duplicate handling

Status: ready-for-agent
Type: task
Blocked by: 01
Execution: Implemented and verified locally. Git branch creation and commit are not complete because the sandbox denied writes to the worktree's common Git metadata.

## Scope and completion criteria

Generate Flyway and jOOQ outputs; implement eventId uniqueness, content comparison, partial rejection, and ACK after commit; verify overload, timeout, rollback, and response loss.

Preserve the applicable contracts in the [agreed spec](../spec.md) and [final review](../final-review.md). Distinguish implementation, execution, and failure evidence. Assess completion after recording the outputs and actual verification.

## Comments

2026-10-09: Created after the full Q127 agreement.

2026-10-11: Implemented the Ktor ingestion route, strict contract and semantic validation, collection-key check, partial per-event rejection, PostgreSQL transaction handling, `eventId` uniqueness, exact duplicate-content comparison, and post-commit ACK behavior. Added Flyway `V1` with typed columns and JSONB, a receivedAt/eventId cursor index, 7 jOOQ Java outputs prepared for Git tracking, clean temporary-schema generation and drift verification, and Testcontainers coverage for concurrent duplicates, conflicts, overload admission, lock and statement timeouts, rollback, connection reuse, and response loss after commit. Added ADR-0004 for the Java 17-compatible jOOQ line, normal local timeout defaults, collection-key header, invalid repeated-ID groups, and cancellation behavior.

Verification commands and results:

- `scripts/setup-toolchain.sh` — passed; installed checksum-verified Temurin 17.0.20.1+1, oasdiff 1.33.0, and openapi-generator-cli 7.26.0 in `.tools/`.
- `cd server && export JAVA_HOME="$PWD/../.tools/jdk-17.0.20.1+1" && export GRADLE_USER_HOME="$PWD/../.tools/gradle-home" && ./gradlew test verifyJooq --no-daemon` — passed; 13 server tests passed, Flyway applied `V1` to PostgreSQL 17.6, and clean jOOQ regeneration matched all 7 tracked generated files.
- `export JAVA_HOME="$PWD/.tools/jdk-17.0.20.1+1" && export GRADLE_USER_HOME="$PWD/.tools/gradle-home" && export npm_config_cache="$PWD/.tools/npm-cache" && scripts/verify-contract.sh` — passed; ended with `ALL CONTRACT CHECKS PASSED`.
- `git switch -c impl/02-server-ingestion` — failed before changing Git state: the sandbox returned `Operation not permitted` while creating the common repository ref lock.
- `git add .scratch/signaldock-portfolio/issues/02-server-ingestion.md docs/adr/0004-server-runtime-and-receipt-boundaries.md gradle/libs.versions.toml server` — failed before staging: the sandbox returned `Operation not permitted` while creating the worktree index lock.
- Commit — not run because branch creation could not write the common Git metadata. Push and PR creation were not run as requested.

2026-10-11: Verified and fixed two ingestion validation review findings.

- `cd server && JAVA_HOME=$PWD/../.tools/jdk-17.0.20.1+1 GRADLE_USER_HOME=$PWD/../.tools/gradle-home ./gradlew test --tests local.signaldock.server.IngestValidationTest --no-daemon` before the fix — failed as expected; the 256 KiB nested-array case produced `StackOverflowError`, and the `+300000` year case passed validation instead of returning `INVALID_OCCURRED_AT`.
- The strict JSON scanner now rejects a container deeper than depth 8 as `MALFORMED_REQUEST`. The `occurredAt` validator now rejects an `Instant` whose UTC year is outside `1..9999` as per-item `INVALID_OCCURRED_AT`.
- `cd server && JAVA_HOME=$PWD/../.tools/jdk-17.0.20.1+1 GRADLE_USER_HOME=$PWD/../.tools/gradle-home ./gradlew test --tests local.signaldock.server.IngestValidationTest --no-daemon` after the fix — passed; all 7 validation tests passed.
- `cd server && JAVA_HOME=$PWD/../.tools/jdk-17.0.20.1+1 GRADLE_USER_HOME=$PWD/../.tools/gradle-home ./gradlew test verifyJooq --no-daemon` — passed on the final code; all 15 server tests passed, Flyway applied `V1` to PostgreSQL 17.6, and `verifyJooq` regenerated the expected 7 files without drift.
- Commit — not run as requested.
