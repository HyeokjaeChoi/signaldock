# Design index

Read the listed sections before you edit or review files in an area.
Refer to sections by heading text.
This file is the only place for area guidance (no per-area `.claude/rules` files). Each `##` heading names its paths in backticks. `scripts/check-repo-rules.sh` fails when a top-level directory has no section or a named path has no files. To add an area, add a section here.

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
- ADR-0014 (`docs/adr/0014-sdk-device-measurement-harness.md`), for `MeasurePhases.kt` and `scripts/device-measure.sh` (issue 24)
- ADR-0017 (`docs/adr/0017-sdk-crash-safety.md`), for the public API failure behavior
- ADR-0018 (`docs/adr/0018-sdk-release-r8-measurement.md`), for `sdk/measure-app` and `sdk/sdk-android/src/measure` (issue 33)
- ADR-0019 (`docs/adr/0019-sdk-public-api-check.md`), for `sdk/sdk-android/api/sdk-android.api` and the `apiCheck` and `apiDump` tasks (issue 35)
- ADR-0020 (`docs/adr/0020-sdk-host-app-cost.md`), for the cost of the SDK in a host app (issue 36)

Public API change: update the dump (`sdk/gradlew -p sdk :sdk-android:apiDump`), the integration guide, and `CHANGELOG.md` in the same PR.

Integration guide: when you change the public API, its KDoc, or the behavior that it describes, update `docs/integration-guide.md` and `docs/integration-guide.ko.md` in the same PR. The guide samples are in `sdk/sdk-android/src/test/kotlin/local/signaldock/IntegrationGuideSamplesTest.kt` (issue 34).

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
- ADR-0022 (`docs/adr/0022-server-tracking-plan-status.md`), for the plan status (`TrackingPlan.kt`, `TrackingPlanFile.kt`, `V2__add_plan_status.sql`, and the `planStatus` filter and counts) (issue 39)
- ADR-0026 (`docs/adr/0026-server-readiness-and-restart.md`), for `/health`, `/ready`, and the Compose `server` health check and restart policy (issue 45)

Spec (`.scratch/signaldock-portfolio/spec.md`):
- 2. Agreed stack and boundaries
- 5. Server receipt and duplicate handling
- 6. User screens and distribution, Dashboard (for `server/query`)
- 8, B. Errors, ACKs, and data representation
- 11. Delegated performance measurement decisions
- 12. Dashboard role confirmation and default delegation
- Q153 and Q155 (optional tracking plan, server marks plan status)

Also read `.scratch/signaldock-portfolio/dashboard-verification-defaults.md` before you edit `server/query` or the query routes.

## `openapi/`, `fixtures/`, `generated/`, `contract-check/`

Architecture (`architecture.md`):
- Contracts, generated sources, and JSON

ADRs:
- ADR-0002
- ADR-0022 (`docs/adr/0022-server-tracking-plan-status.md`), for the additive `planStatus` change and the baseline update (issue 39)

Spec (`.scratch/signaldock-portfolio/spec.md`):
- 7. Completion criteria and verification plan (contract rows)
- 8, B. Errors, ACKs, and data representation
- 8, C. Small modules and local execution
- 9. Technical conditions to check on entry into implementation
- Q155 (plan status)

## `sdk/demo/` (Android demo app, issue 05)

Architecture (`architecture.md`):
- Components and responsibilities

ADRs:
- ADR-0005, section "Demo app: feature modules and unidirectional data flow"
- ADR-0021 (`docs/adr/0021-tracking-plan-codegen.md`), for how `sdk/demo/core/data` sends its events through the generated functions (issue 37)

Spec (`.scratch/signaldock-portfolio/spec.md`):
- 6. User screens and distribution, Demo and diagnostics
- Q152 to Q154 (tracking plan)

## `tracking-plan/` (optional tracking plan and code generation, issue 37)

`plan.json` is the source. `generate.mjs` validates it and writes `generated/kotlin/TrackingPlan.kt` (compiled by `sdk/demo/core/data`), `generated/typescript/events.ts`, and `generated/manifest.json`. `generate.test.mjs` checks plan validation and that the Kotlin and TypeScript outputs agree. Change a generated file only by changing `plan.json` or `generate.mjs`, then run `node tracking-plan/generate.mjs`. `scripts/verify-contract.sh` (step 7) checks the drift. The SDK (`sdk/sdk-android`) and its public API dump must not change for this area; the consumer app stays on plain `track()`. The server reads the same `plan.json` (path in `SIGNALDOCK_TRACKING_PLAN`, mounted by Compose) and marks stored events (ADR-0022); a change of the plan format needs a change of `server/app/.../TrackingPlanFile.kt` in the same PR.

ADRs:
- ADR-0002 and ADR-0012, for the generated-and-tracked output pattern
- ADR-0021 (`docs/adr/0021-tracking-plan-codegen.md`)
- ADR-0022 (`docs/adr/0022-server-tracking-plan-status.md`)

Spec (`.scratch/signaldock-portfolio/spec.md`):
- 3. SDK public behavior, Collection
- Q64 (name and key rules)
- Q152 to Q155 (tracking plan, server plan status)

## `compatibility/` (independent consumer app, issue 06)

Architecture (`architecture.md`):
- Independent distribution and verification

ADRs:
- ADR-0009 (`docs/adr/0009-maven-zip-and-consumer-app.md`)
- ADR-0020 (`docs/adr/0020-sdk-host-app-cost.md`), for `src/sdk`, `src/nosdk`, and `-Psignaldock.sdk=false` (issue 36)

Spec (`.scratch/signaldock-portfolio/spec.md`):
- 6. User screens and distribution, Reproduction and deliverables
- Q151 (host cost)

## `dashboard/`

Architecture (`architecture.md`):
- Components and responsibilities
- DB time budgets and queries

ADRs:
- ADR-0002
- ADR-0007 (`docs/adr/0007-dashboard-query-api.md`)
- ADR-0008 (`docs/adr/0008-dashboard-and-compose-delivery.md`)
- ADR-0015 (`docs/adr/0015-web-ui-languages.md`), for the UI texts, the language switch, and `web-shared/`
- ADR-0016 (`docs/adr/0016-telemetry-style-ui.md`), for the layout, the KPI cards, the theme, and the shared tokens (issue 28)
- ADR-0022 (`docs/adr/0022-server-tracking-plan-status.md`), for the plan status badge, filter, and violations card (issue 39)

Spec (`.scratch/signaldock-portfolio/spec.md`):
- 6. User screens and distribution, Dashboard
- 8, B. Errors, ACKs, and data representation
- 11. Delegated performance measurement decisions
- 12. Dashboard role confirmation and default delegation
- Q78 and Q79 (project timezone), Q107 to Q109 (contract errors and retry), Q135 to Q137 (UI languages), Q139 and Q140 (presentation redesign, issue 28), Q155 (plan status)

Also read `.scratch/signaldock-portfolio/dashboard-verification-defaults.md`.

## `web-shared/` (code that `dashboard/` and `perf/report/` share, issues 27 and 28)

Plain TypeScript and CSS with no `package.json` and no `node_modules`. Both pages import it by relative path. Its tests run with the Dashboard (`cd dashboard && npm test`). `web-shared/i18n/` holds the language code (issue 27). `web-shared/theme/` holds the design tokens (`tokens.css`), the shared element styles (`base.css`), the theme choice (`theme.ts`, `react.ts`), and the shared chart style (`chart.ts`) (issue 28). Change a color only in `tokens.css`; the contrast test checks it.

ADRs:
- ADR-0015 (`docs/adr/0015-web-ui-languages.md`)
- ADR-0016 (`docs/adr/0016-telemetry-style-ui.md`), for `web-shared/theme/`
- ADR-0008 and ADR-0013, for the separate builds and lockfiles that it must not merge

Spec (`.scratch/signaldock-portfolio/spec.md`):
- Q78 and Q79 (project timezone)
- Q135 to Q137 (UI languages)
- Q139 and Q140 (presentation redesign, issue 28)

## `perf/` (server load measurement harness, issue 23; SDK device measurement results, issue 24; report page, issues 23 and 27)

For issue 24 (`perf/sdk_summarize.py`, `perf/sdk-results/`, `scripts/device-measure.sh`) read ADR-0014 (`docs/adr/0014-sdk-device-measurement-harness.md`) and spec section 7 (the load sentences under the table).

Architecture (`architecture.md`):
- DB time budgets and queries

ADRs:
- ADR-0004
- ADR-0007
- ADR-0008
- ADR-0012
- ADR-0013 (`docs/adr/0013-server-load-measurement-harness.md`)
- ADR-0014 (`docs/adr/0014-sdk-device-measurement-harness.md`), for the SDK results that `perf/report/` shows
- ADR-0018 (`docs/adr/0018-sdk-release-r8-measurement.md`), for the release-build SDK results (issue 33)
- ADR-0020 (`docs/adr/0020-sdk-host-app-cost.md`), for `scripts/host-cost-measure.sh` and `perf/sdk-results/host-cost-api37/` (issue 36)
- ADR-0015 (`docs/adr/0015-web-ui-languages.md`), for the report page languages and `web-shared/`
- ADR-0016 (`docs/adr/0016-telemetry-style-ui.md`), for the KPI strip, the table of contents, the overload bands, and the shared tokens (issue 28)

Spec (`.scratch/signaldock-portfolio/spec.md`):
- 7. Completion criteria and verification plan
- 11. Delegated performance measurement decisions
- Q124 and Q125 (normal-operation waits stay separate from benchmark values)
- Q151 (SDK host cost: APK size, initialization time, PSS)
- Q135 to Q137 (UI languages)
- Q139 and Q140 (presentation redesign, issue 28)

Also read `.scratch/signaldock-portfolio/performance-plan.md`; it is authoritative for the fixed conditions, load, and acceptance.

## `scripts/`, `.githooks/`, `.github/workflows/`, `gradle/`

Architecture (`architecture.md`):
- Independent distribution and verification

ADRs:
- ADR-0002
- ADR-0021 (`docs/adr/0021-tracking-plan-codegen.md`), for step 7 of `scripts/verify-contract.sh` (issue 37)
- ADR-0009 (`docs/adr/0009-maven-zip-and-consumer-app.md`), for `scripts/verify-consumer.sh` and `scripts/release.sh` (issue 32; release notes in `docs/releases/`)
- ADR-0020 (`docs/adr/0020-sdk-host-app-cost.md`), for `scripts/host-cost-measure.sh` (issue 36)

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
