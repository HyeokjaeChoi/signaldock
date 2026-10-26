# Next scope: product features vs current design

Read-only research. Refs are `path:line`. Size: S (days), M (1-2 weeks), L (multi-week). "Contract 1 additive" = optional change inside contract version 1 (ADR-0002:13, Q65). "Contract 2" = incompatible change (ADR-0002:13).

## Shared baseline
- Scope excludes automatic events, identify, campaign/push, funnels, multiple projects, public operation (`.scratch/signaldock/spec.md:17`, `:133`; `docs/integration-guide.md:440-441`).
- Identity is per installation only: `installationId` is a batch-level field (`openapi/contract-v1.yaml:515-520`), stored per event (`server/ingestion/.../IngestRules.kt:84`). ADR-0001 identifies installations "without linking them to logged-in users" (`docs/adr/0001-installation-scoped-identity.md:7`).
- Glossary says installation ID is "avoid user ID" (`GLOSSARY.md:17-19`). This is a naming rule, not a ban on a user ID.
- Ingest is strict: unknown top-level event fields return `UNKNOWN_FIELD` (`server/app/.../RequestParsing.kt:85-86`; `openapi/contract-v1.yaml:574`). Free-form `properties` accept only string, number, boolean (`openapi/contract-v1.yaml:562-566`; ADR-0002:17).
- Storage has no project, user, or session column (`server/persistence/.../V1__create_events.sql:2-11`). Only `plan_status` was added (`V2__add_plan_status.sql:4-5`).
- No delete or TTL path exists (`docs/adr/0028-server-production-features-excluded.md:23`, `:32`).

## 1. Automatic events (app open, screen view, session)
- Reuse: `track()` is the only entry point (`sdk/sdk-android/.../SignalDock.kt:54`). Opt-out already covers it (`docs/adr/0034-sdk-opt-out.md:18`). Plan generation can name the events (`docs/adr/0021-tracking-plan-codegen.md:11`). Names and properties are free-form at ingest, so no contract change is needed.
- New work: an SDK-internal collector on `Application.ActivityLifecycleCallbacks`. ADR-0006 already uses that hook for scheduling and says `lifecycle-process` is not used (`docs/adr/0006-sdk-delivery-decisions.md:38`). Real-activity behavior is unverified (`:46`). Session ID needs a generator and a rule (no session concept exists in the data model). Screen name needs a host-supplied source; SDK must stay Compose-free (`spec.md:26`).
- Contract: none (contract 1).
- Privacy: screen names can embed IDs from routes. Plan check marks unknown events but never drops them (`docs/adr/0022-server-tracking-plan-status.md:7`).
- Size: M.

## 2. identify (user ID)
- Reuse: `installationId` filter and list path (`server/query/.../Query.kt:93`, `:211`; `server/persistence/.../QueryStore.kt:165`).
- New work: decide what a "user" is (login-linked ID vs app-supplied ID). Amend ADR-0001 scope, spec section 1 exclusion, and the glossary (add "User ID"). Migration V4 for a nullable `user_id` with an index. Query filter. Dashboard column. A new ADR is required (AGENTS.md judgment rule 2).
- Contract options:
  - (a) Free-form property `user_id`: no contract change (`contract-v1.yaml:562-566`; ADR-0002:17 exempts property keys). But it is per event, not identity, and cannot be deleted by key.
  - (b) Top-level optional `userId` on `EventIngest` or the batch: contract 1 additive under Q65 with server deployed first (ADR-0002:13). Risk: an older server rejects the field with `UNKNOWN_FIELD` (`RequestParsing.kt:85-86`), and the SDK holds the queue (ADR-0002:17; Q67). Contract 2 only if a field becomes required or changes meaning.
- Privacy risk: high. No delete function (ADR-0028:23). Dashboard shows all events to the one shared query-key holder (ADR-0044 risk table, `docs/adr/0044-cloud-demo-operation-replaces-adr-0028.md:33`). Opt-out does not remove server rows (ADR-0034:36).
- Size: M (a) to L (b with erasure).

## 3. Funnels
- Reuse: `GET /v1/event-counts/by-name` and `by-time` give per-step counts (`openapi/contract-v1.yaml:265`, `:296`; `docs/adr/0007-dashboard-query-api.md:17-18`).
- New work: a sequence query per installation (step order, time window). Needs a new endpoint and a query design. ADR-0007 adds no index and says to add one only after measurement (`docs/adr/0007-dashboard-query-api.md:28`). A funnel index on `(installation_id, name, occurred_at)` needs a migration and a perf run. Choose `occurredAt` (client, unverified) or `receivedAt` (server).
- Contract: additive new GET path (contract 1). Response schema is new.
- Privacy: installation-level behavior trail. Low identity risk without `user_id`.
- Size: M (installation funnel) to L (with user funnel after feature 2).

## 4. Campaign / push
- Reuse: campaign attributes can go in free-form properties (contract 1, `contract-v1.yaml:562-566`). Opt-out and the kill switch apply to ingest (`docs/adr/0037-server-collection-kill-switch.md:5`, `:19`).
- New work: push token registration. Requires a messaging SDK dependency (none in repo). That changes the published dependency tree (`docs/adr/0033-sdk-dependency-baseline.md:11`) and the APK size budget (`docs/adr/0023-ci-device-tests-and-size-budget.md:16`). The server has no outbound push path, no provider credential handling, and no token table. Token rotation and unregister need endpoints.
- Contract: token register is additive (contract 1). Unregister or delete semantics, or a change to installation identity, may need contract 2. Contract 2 policy is unresolved (see Risks).
- Privacy: high. A push token is a device-reachable identifier linked to the installation.
- Size: L.

## 5. Multiple accounts / projects
- Current: one collection key (`server/app/.../Application.kt:169`), one query key (`:171`), one time zone (`Application.kt:203`; `docs/adr/0007-dashboard-query-api.md:24`). `event_id` is the global primary key (`V1__create_events.sql:2`), so cross-project duplicates collide.
- New work: `project_id` column (V4). Change the primary key to `(project_id, event_id)`. Key-to-project mapping. Per-project time zone and per-project `timeZone` in responses (`docs/adr/0007-dashboard-query-api.md:24`). Dashboard project selector. Account management needs login (ADR-0028:24; spec 1 exclusion).
- Contract: key-to-project mapping can stay server-side (no change). A project query parameter is contract 1 additive, but strict parameter parsing rejects unknown params (`docs/adr/0007-dashboard-query-api.md:30`), so server-first deploy is required. Per-project timezone in `by-time` may be a breaking response change.
- Privacy: cross-project data separation becomes a security requirement; the current query key is one shared secret (ADR-0007:9).
- Size: L.

## Risks and open decisions
- Contract 2 policy conflict. Spec Q159 (`.scratch/signaldock/spec.md:418`, agreed 2026-10-16) sets a 12-month minimum plus a traffic threshold. `.scratch/signaldock/remaining-decisions.md:35` says the policy is still undecided. Resolve before any contract 2 work.
- Spec 1 (`spec.md:17`) excludes all five features. Each needs a new Q agreement, not just an ADR.
- Erasure: no delete path (ADR-0028:23). Any user ID or push token needs a delete design first.
- Server-first rollout for any new optional field: an old server rejects it (`RequestParsing.kt:85-86`) and the queue holds (ADR-0002:17).
- Client-side `occurredAt` is client time (`openapi/contract-v1.yaml:544-551`); funnel timing relies on it.
- Not verified: `ActivityLifecycleCallbacks` on real devices (`docs/adr/0006-sdk-delivery-decisions.md:46`).
