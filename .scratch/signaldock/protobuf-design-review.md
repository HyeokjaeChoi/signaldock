# Protobuf contract review for SignalDock

Status: on 2026-10-08, the user selected the follow-up recommendation to standardize on OpenAPI + JSON. ADR-0002 is the current decision. The text below records the review that changed the recommendation. It does not record Protobuf adoption. Implementation and execution verification are pending. [ADR-0002](../../docs/adr/0002-openapi-json-contract.md)

## Overall conclusion

Follow-up review changed the recommendation: the user raised the cost of managing Dashboard JSON and ingestion Protobuf together. OpenAPI also supports automatic generation and version tracking, so the current recommendation is **JSON transport for all HTTP APIs, with OpenAPI as the contract source of truth**. The Protobuf-first recommendation below is an earlier assessment, not the current recommendation. The user's concern is not treated as a new agreement; the Q32 choice remains pending in this review record. At this stage, no separate Protobuf benefit has been verified to justify the cost of two codecs, generation paths, and numeric/null conversion rules.

Proposed setup: define ingestion/query endpoints and reusable schemas in `contracts/openapi.yaml`. Generate Kotlin models for the SDK/server and TypeScript models for the Dashboard from the same spec. Include only the models each component needs. Publishing a separate shared Kotlin artifact is not required. Separate ingestion request and query response models by role, and reuse schemas with common semantics through `$ref`. Pin the generator and options, then regenerate and compile both sides. Kotlin kotlinx_serialization and the TypeScript generator have official support, but actual generated handling of dynamic properties, null, and partial decoding remains unverified. [Kotlin generator](https://openapi-generator.tech/docs/generators/kotlin/), [TypeScript fetch generator](https://openapi-generator.tech/docs/generators/typescript-fetch/)

Manage history through Git commits/tags and release schema snapshots. Use a CI comparison tool such as oasdiff for breaking changes. `openapi` is the specification syntax version, and `info.version` is the document version. Manage SDK artifact versions and endpoint support lifetimes separately. These fields alone do not guarantee compatibility. Retain old-version fixtures and execution checks for storage and ACK behavior. `oasdiff` is a candidate and has not been installed or run. [OAS Info](https://spec.openapis.org/oas/v3.1.1.html#info-object), [oasdiff](https://github.com/oasdiff/oasdiff)

### Earlier comparative assessment

The separate research report judged OpenAPI model generation to have lower change cost under the existing JSON agreement and short schedule. The main agent agrees with that assessment of current integration cost. However, because the user prioritizes the maintenance value of DTO generation and protocol change management, the recommendation at that time was to make **schema-first Protobuf + binary HTTP the leading design candidate**. This does not establish shorter total implementation time or completed user adoption. OpenAPI also provides automatic generation, so generation itself is not a Protobuf-only advantage. [separate research report](protobuf-contract-research.md)

The recommended scope is the SDK and ingestion API event envelope, batch requests, and results. Each component generates models from one `.proto` source. A separate shared JVM DTO artifact is unnecessary. Dashboard queries retain JSON + kotlinx.serialization. Do not add gRPC or a cloud schema registry. Limit Q29/Q31 changes to ingestion, and record agreement only after the user responds.

Use official Java/Kotlin lite as the reference candidate without finalizing the generator/runtime combination. Official Kotlin generation provides a DSL over Java messages. Square Wire Kotlin models are an alternative. Compare public API exposure, runtime dependencies, and serialization integration together, instead of selecting solely on whether output is pure Kotlin. [Kotlin generation](https://protobuf.dev/reference/kotlin/kotlin-generated/), [Wire](https://square.github.io/wire/)

The adoption check will focus on a small round trip: SDK request using generated models → Ktor decoding → per-event ACK, plus old-schema fixtures and dependency/release-build checks in a separate Android consumer app. The plan is to confirm feasibility and cost before implementing the actual project. These checks were not run during this research. Properties types will be decided in follow-up grilling.

## Additional integration costs confirmed

- The Retrofit 3.0.0 source Protobuf converter handles `MessageLite`, but its dependency is full `protobuf-java:3.25.7`. Check duplicate classes and dependency resolution with lite. The main agent directly rechecked build.gradle and the version catalog. This is a fact about the researched version, not a selected project version. [build](https://github.com/lysine-dev/retrofit/blob/3.0.0/retrofit-converters/protobuf/build.gradle), [catalog](https://github.com/lysine-dev/retrofit/blob/3.0.0/gradle/libs.versions.toml)
- Java lite documentation explicitly states that API/ABI stability is not guaranteed. The general cross-version policy below does not guarantee a lite combination. Pin the generator/runtime and verify an actual consumer app. [lite v33.0](https://github.com/protocolbuffers/protobuf/blob/v33.0/java/lite.md)
- ProtoJSON introduces separate mapping and codec boundaries even when transport remains JSON. It is excluded from the minimal recommendation because Google JsonFormat requires full models and integration with lite adds cost. This does not mean ProtoJSON itself is unsuitable. [research section 1](protobuf-contract-research.md#1-generated-models-and-http-integration)
- Numbers in dynamic `Struct/Value` are doubles. Do not put large integer IDs or exact monetary values into numbers without a type decision. Define allowed property types first, then choose Struct or the smallest required value model. Do not start with a general recursive type system. [struct.proto](https://github.com/protocolbuffers/protobuf/blob/v33.0/src/google/protobuf/struct.proto)

## Decision criteria

The user asked for a review of the maintenance value of automatic DTO generation and schema versioning. Reduced payload size and gRPC adoption are not current requirements. The existing JSON choice is the comparison baseline. It neither excludes Protobuf nor makes the new request an instruction to adopt it.

1. Can one contract reproducibly generate types for the SDK and server?
2. Can a new server communicate with already deployed SDKs? Compiling both latest codebases together is insufficient.
3. Does the approach retain the existing collection, retry, ACK, and partial-acceptance contracts?
4. Can generation tools and runtime dependencies be managed in a separate Maven consumer app?

## Impact on existing agreements

- Q27 Retrofit + OkHttp and Q19 Ktor can remain as HTTP layers. Do not couple a transport-format change to an HTTP framework change.
- Q29 kotlinx.serialization JSON DTOs and Q31 JSON contracts are subject to review for ingestion. Dashboard query JSON can remain a separate path. Do not revise the original agreement before Protobuf adoption is settled.
- Retain Q14 customer-defined event names and properties. Generating transport envelope types and statically enforcing a product-specific event taxonomy are separate scopes.
- Q22 accepts valid events in a batch that can be parsed. Validate individual events after parsing succeeds. Do not automatically add a framing design that recovers valid events from input that cannot be parsed as a whole.
- Under Q7/Q21, do not delete queue entries before DB commit and a verifiable ACK. Unknown response types or ambiguous item associations must not count as success.
- Idempotency comparison uses agreed event-content equality, not transport-byte equality. Define DB storage and comparison policies for new fields together.

## Maintenance verification conditions

Code generation reduces manual synchronization of DTO fields. The proposal does not combine the public SDK API, Room entities, and jOOQ records into one generated transport type. Conversions at each boundary may remain necessary. Hide generated types from the public SDK API so protocol changes do not directly require customer app source changes.

The minimum checks for the first release implementation follow. They have not been run.

- Generate and compile SDK/server types from the same schema with pinned tool versions.
- Confirm that a new server receives old SDK requests and that an old SDK safely handles the new server's ACK.
- Check whether a server that does not understand a new field can lose it in the DB and still report success. Distinguish successful parsing from successful storage of the intended information.
- Process a batch containing invalid individual events and valid events. Do not invent per-item successes for an unparseable request.
- Use a separate consumer app's release build with the Maven ZIP to verify generated classes, runtime dependencies, and R8 boundaries.

## Versioning proposal

Do not combine the following four concerns into one version number.

| Target | Management basis |
| --- | --- |
| SDK artifact version | Customer app installation coordinates and public API/behavior changes |
| Protocol package | Consider a new contract such as v2 for breaking changes. Do not change it for every optional field addition. |
| Schema revision | Git commit or release snapshot. This checkout did not yet have a Git repository at the time of the review. |
| protoc/plugin/runtime | Reproducible generation and host app dependency resolution. Pin tool versions and check official compatibility ranges. |

Never reassign a field number after use. Reserve its number and name on removal. Add a new field and define a handling period for the old field instead of replacing its type or meaning. Even an optional field addition breaks compatibility if server validation requires old requests to supply the new value. [Proto3](https://protobuf.dev/programming-guides/proto3/)

Buf's `buf breaking` compares the current schema with an earlier schema from Git, a snapshot, or another supported source. The initial proposal uses conservative FILE rules to check generated source, JSON, and binary compatibility. This does not detect business semantic changes or missing storage. Retain baseline schemas for supported releases as well as the main branch. A BSR cloud registry is not required. [Buf breaking](https://buf.build/docs/breaking/)

Official runtime policy prohibits combining new generated code with a runtime older than its generation tool. Separate SDK/server network compatibility from generated-code/runtime compatibility within one process. [Cross-version runtime guarantee](https://protobuf.dev/support/cross-version-runtime-guarantee/)

Ktor's default `protobuf()` example uses kotlinx.serialization. Do not treat it as the same codec as messages generated from `.proto` with protoc. If adopted, connect generated parsers to ingestion route byte input/output, or add a small converter as needed. This can coexist with the existing Dashboard JSON configuration. [Ktor serialization](https://ktor.io/docs/server-serialization.html)
