---
status: accepted
---

# Module structure for the SDK, demo app, and server

The user agreed on 2026-10-11. The goal is a structure that follows Google and Ktor guidance, keeps maintenance cheap, and lets an agent find a feature from its module or package name.

## SDK: one publication module, feature packages

The SDK stays one Gradle publication module (`sdk/sdk-android`) that ships one AAR. Q86 still applies. Inside it, each concern gets its own package under `local.signaldock.internal`:

| Package | Owns |
|---|---|
| `init` | initialization coordinator, composition point (manual DI, Q87), composition seams (logger, clock, id generator) |
| `collection` | the `track` path: validate, build the transport event, size it, store it |
| `validation` | event name, key, value, and size validation |
| `storage` | Room database, DAOs, entities, installation id |
| `delivery` | batching, HTTP transport, ACK application (issue 04) |
| `scheduling` | WorkManager and foreground triggers, backoff (issue 04) |
| `diagnostics` | diagnostics snapshot mapping |
| `transport` | generated DTO adapters and canonical size calculation |

The Room database class `SignalDockDatabase` stays in `local.signaldock.internal`, because Room names the exported schema directory after the class's fully qualified name, and moving it would rename `sdk/sdk-android/schemas/...`.

Public API types stay in `local.signaldock`. Implementation types stay `internal`.

Reasons:

- The SDK must ship one AAR. Fusing several Gradle modules into one AAR needs the Android fused library plugin. It is still Preview in AGP 9.x and does not package transitive dependencies ([fused library](https://developer.android.com/build/publish-library/fused-library)).
- Kotlin `internal` is per module ([visibility](https://kotlinlang.org/docs/visibility-modifiers.html)). Splitting would force the generated transport DTOs and implementation types to become public, which conflicts with architecture.md "Components and responsibilities".
- Google's modularization guide makes modularization optional unless a project needs reuse, strict visibility control, or Play Feature Delivery ([modularization](https://developer.android.com/topic/modularization)).

Unidirectional data flow does not apply to the SDK. The SDK has no Compose or ViewModel dependency.

Revisit this when the fused library plugin becomes stable.

## Demo app: feature modules and unidirectional data flow

The demo app (issue 05) uses feature-based modules, following the [modularization patterns](https://developer.android.com/topic/modularization/patterns):

| Module | Owns |
|---|---|
| `:demo:app` | entry point, navigation, composition |
| `:demo:feature:shop` | shopping flow screens and ViewModel |
| `:demo:feature:diagnostics` | diagnostics screen and ViewModel |
| `:demo:core:ui` | shared Compose theme and components |
| `:demo:core:data` | the SDK facade the features use |

Feature modules do not depend on each other. Each screen follows the [UI layer guide](https://developer.android.com/topic/architecture/ui-layer): a ViewModel exposes one immutable UI state through `StateFlow`, the screen collects it with `collectAsStateWithLifecycle()`, and user actions go up as events. Spec section 6 still applies: one `track` per user action, no recollection on recomposition.

## Server: layered Gradle modules

The server build (`server/`) splits into four Gradle modules, following the multi-module option in the [Ktor application structure](https://ktor.io/docs/server-application-structure.html):

| Module | Owns | Depends on |
|---|---|---|
| `:app` | Ktor setup, routes, HTTP status mapping, generated transport DTOs | `:ingestion`, `:query`, `:persistence` |
| `:ingestion` | request validation rules, receipt and duplicate decisions, domain types | none |
| `:query` | Dashboard query parameter validation, period and page limits, cursor and bucket rules (issue 14, ADR-0007) | `:ingestion` (for `PropertyValue` only) |
| `:persistence` | Flyway migrations, jOOQ generated code, transactions, timeouts, ingestion and query SQL | `:ingestion`, `:query` |

`:ingestion` and `:query` do not compile against jOOQ, Flyway, Ktor, JDBC, serialization, or the generated DTOs. architecture.md "Contracts, generated sources, and JSON" requires public API types, jOOQ records, and DTOs to stay separate by role. The generated DTOs are `internal` and stay in `:app`. Later features add their own modules in the same pattern.

There is no official layout for Ktor with jOOQ and Flyway. This split is common practice. It is used because the boundaries are then enforced by the compiler instead of by package names.

## Agents

`docs/design-index.md` maps areas to modules and packages, so an agent can find the design sections for a feature from its path.
