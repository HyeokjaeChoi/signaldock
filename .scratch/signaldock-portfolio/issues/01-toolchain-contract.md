# 01. Toolchain and OpenAPI round trip

Status: ready-for-agent
Type: task
Blocked by: None
Execution: Implemented; awaiting PR review (see the 2026-10-09 implementation comment)

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
- `openapi/contract-v1.yaml`: ingestion API, `SignalDock-Contract-Version` header, per-item ACK, error model. `fixtures/contract/contract-v1.baseline.yaml` is the prior-schema baseline for oasdiff.
- Pinned template override `openapi/templates/oneof_class.mustache` fixes two upstream bugs: (1) oneOf number branch never matched JSON numbers (BigDecimal not flagged primitive); (2) `BigDecimal.serializer()` does not exist.

### Execution evidence
Each claim names its command, date, and result. Source: commit 5c264cd (2026-10-09 text), PR #9 description Test report (2026-10-11, commit 681b6df), and PR #9 merge history. A claim with no recorded command is marked "not verified (command not recorded)".

#### 2026-10-09 claims (commit 5c264cd; no command recorded for any of them)
- `scripts/codegen.sh` generates Kotlin (kotlinx_serialization) + TypeScript (typescript-fetch) models into `generated/` (tracked per Q91). Run on 2026-10-09: not verified (command not recorded). Drift check passed on 2026-10-11 (see below).
- Kotlin models compile with kotlinc 2.4.21 + serialization plugin (25 classes). Date: 2026-10-09. Command: not verified (command not recorded). Result: not verified.
- `contract-check` round-trip tests: 5/5 pass. Date: 2026-10-09. Command: not verified (command not recorded). Count 5/5: not verified. Gradle run on 2026-10-11 passed, count not recorded (see below).
- TypeScript models compile with tsc 7.0.2 (strict); union type `boolean | number | string`. Date: 2026-10-09. Command: not verified (command not recorded). Typecheck and contract-check passed on 2026-10-11 (see below).
- oasdiff "self-diff `{}`" (2026-10-09): not valid evidence. It compared the contract with itself. Superseded by commit 32f4d7f (2026-10-11).
- Fixture coverage (unicode, +09:00, BigDecimal `1.0`/`0.0`, null/nested rejection, `-0.0` to `0.0`): describes fixture content, not a run. Not verified as a separate run.
- Independent builds: `sdk/`, `server/`, `contract-check/` each have their own settings and wrapper. This is a file check, not a build result. Build results are in the 2026-10-11 table.

#### Full run on 2026-10-11 (commit 681b6df, macOS 26 Darwin arm64, repo root; source: PR #9 Test report)
- Pre-push hook (`.githooks/pre-push`) ran the full `scripts/verify-contract.sh` for the push of 681b6df. Result: `ALL CONTRACT CHECKS PASSED`.
- Step-by-step results from the same report:

| Step | Command | Result |
|---|---|---|
| Repo rules | `scripts/check-repo-rules.sh` | Pass |
| 1. Codegen drift | `scripts/verify-contract.sh` step 1 (`scripts/codegen.sh` into a temp dir, `diff -rq` against `generated/`) | Pass |
| 2. Contract baseline | `.tools/oasdiff diff --fail-on-diff fixtures/contract/contract-v1.baseline.yaml openapi/contract-v1.yaml` | Pass |
| 3. Kotlin round trip | `cd contract-check && ./gradlew test --no-daemon` | `BUILD SUCCESSFUL`, round-trip tests passed (count not recorded) |
| 4. TypeScript | `cd dashboard && npm ci && npm run typecheck && npm run contract-check` | `ALL CONTRACT-CHECK ASSERTIONS PASSED` |
| 5. Independent builds | `./gradlew help` in `sdk/` and `server/` (Gradle 8.14.5) | Pass |

- CI job `verify-contract`: not verified. Commit 32f4d7f says CI runs full contract verification, but no CI result is recorded in the sources.
- Not run on 2026-10-11: AGP, Room, Ktor, and other doc-pinned versions in `TOOLCHAIN.md` (PR #9 "Not run").

### Environment limitation
2026-10-09 (commit 5c264cd): Gradle daemon could not run in the sandbox (IPv4/IPv6 localhost conflict + egress proxy). Direct kotlinc and tsc runs were used instead. Command for the failure and for the kotlinc/tsc runs: not verified (command not recorded). No Gradle run is recorded for 2026-10-09.

2026-10-11 (PR #9 Test report): `./gradlew test --no-daemon` (contract-check) and `./gradlew help` (sdk, server) ran on macOS, not the 2026-10-09 sandbox. The report does not say the daemon problem was resolved. Do not read the 2026-10-09 limitation as applying to 2026-10-11.

`scripts/verify-contract.sh` contains the Gradle-based workflow (file check).
