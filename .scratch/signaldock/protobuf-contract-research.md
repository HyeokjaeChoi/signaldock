# SignalDock Protobuf contract research

Reference date: **2026-10-08, Asia/Seoul**. Separate research report by **gpt-6.1-sol**. **Adoption undecided**. Only official documentation and first-party source were used as evidence. Installation, code generation, compilation, builds, execution, performance, and size measurements were not performed. The main agent's final combined assessment is recorded separately in the design review. [design review](protobuf-design-review.md)

## Conclusions and recommendations

Schema-first Protobuf DTO generation is possible. gRPC is not required. It would change the existing Q29 kotlinx.serialization JSON DTO path. Official Kotlin output is a DSL over Java messages. Ktor's default `protobuf()` uses kotlinx.serialization and is not an automatic adapter for protoc-generated models. [Kotlin generation](https://protobuf.dev/reference/kotlin/kotlin-generated/), [Ktor serialization](https://ktor.io/docs/server-serialization.html), [Retrofit Protobuf converter](https://github.com/lysine-dev/retrofit/blob/3.0.0/retrofit-converters/protobuf/src/main/java/retrofit2/converter/protobuf/ProtoConverterFactory.java)

The recommended candidate is OpenAPI models-only generation with JSON retained. This project assessment reflects its smaller change scope under Q29/Q31. Dynamic properties, null, and partial decoding in actual generated output remain unverified. If Protobuf is selected, the minimum candidate is official Java+Kotlin lite + binary HTTP. Adopt neither option until the checks below pass. [selective model generation](https://openapi-generator.tech/docs/customization/#selective-generation), [Kotlin generator](https://openapi-generator.tech/docs/generators/kotlin/), [current agreement](spec.md)

The main risk is the difference between parsing success and storage success. An old server may read a new field and preserve it as an unknown field, while its DB mapper sends an ACK without storing it. If the SDK deletes the queue entry, the new information is lost. Server-first rollout and actual old/new storage and ACK verification are required. This is an inference from Q21's responsibility for successful receipt. [unknown field preservation and loss](https://protobuf.dev/programming-guides/proto3/#unknowns)

## 1. Generated models and HTTP integration

| Option | Confirmed facts and integration boundaries |
| --- | --- |
| Official Java + Kotlin lite | Generates Java messages and a Kotlin DSL together from `.proto`, not independent Kotlin data classes. Configure Android lite output and `protobuf-javalite`. Check generator options for current lite configuration instead of copying only older `optimize_for` examples. [Kotlin generation](https://protobuf.dev/reference/kotlin/kotlin-generated/), [Gradle plugin v0.9.5](https://github.com/google/protobuf-gradle-plugin/blob/v0.9.5/README.md), [lite v33.0](https://github.com/protocolbuffers/protobuf/blob/v33.0/java/lite.md) |
| Square Wire Kotlin | Uses independent Kotlin models, `ProtoAdapter`, and `wire-runtime`. These are not Google `MessageLite` types. Kotlin model generation and gRPC generation are separate. This is an alternative to compare when a Kotlin API is a priority. [Wire](https://square.github.io/wire/), [compiler](https://square.github.io/wire/wire_compiler/) |
| kotlinx.serialization `ProtoBuf` | Produces binary data through serializers for `@Serializable` models. `ProtoNumber` is available, but this differs from protoc-based schema-first DTO generation. [ProtoBuf API](https://kotlinlang.org/api/kotlinx.serialization/kotlinx-serialization-protobuf/kotlinx.serialization.protobuf/-proto-buf/) |

Binary HTTP: Retrofit `converter-protobuf` handles `MessageLite`; `converter-wire` handles Wire `Message`. Ktor integration candidates are a route that limits body size, decodes with a generated parser/adapter, and returns bytes, or a custom `ContentConverter`. HTTP status and per-event ACKs are separate contracts. No gRPC service is required. Do not assume Ktor's default `protobuf()` directly connects protoc models. [Protobuf converter](https://github.com/lysine-dev/retrofit/blob/3.0.0/retrofit-converters/protobuf/src/main/java/retrofit2/converter/protobuf/ProtoConverterFactory.java), [Wire converter](https://github.com/lysine-dev/retrofit/blob/3.0.0/retrofit-converters/wire/src/main/java/retrofit2/converter/wire/WireConverterFactory.java), [Ktor custom converter](https://ktor.io/docs/server-serialization.html#custom-serializer)

ProtoJSON: Google `JsonFormat` accepts `MessageOrBuilder` and `Message.Builder`. It cannot be applied directly to lite `MessageLite`. Wire's official JSON adapters use Moshi/Gson and ignore unknown JSON fields by default. No evidence for an automatic kotlinx.serialization adapter was confirmed. JSON transport alone therefore does not satisfy Q29. Binary exchange between server full models and Android lite models is a possible candidate, but does not resolve Android ProtoJSON codec integration. [JsonFormat v33.0](https://github.com/protocolbuffers/protobuf/blob/v33.0/java/util/src/main/java/com/google/protobuf/util/JsonFormat.java), [Java lite boundaries](https://protobuf.dev/reference/java/java-generated/), [Wire JSON](https://square.github.io/wire/wire_json/)

ProtoJSON outputs int64 as decimal strings and rejects unknown fields by default. Field and enum names are also part of the contract. Do not assume binary additive compatibility applies unchanged. Keeping Dashboard query JSON separate from ingestion binary is a design proposal. [ProtoJSON rules](https://protobuf.dev/programming-guides/json/)

## 2. OpenAPI codegen comparison

The `kotlin` generator's `jvm-retrofit2` + `serializationLibrary=kotlinx_serialization` is a documented candidate combination. First consider generating only models and connecting them to handwritten Retrofit interfaces and Ktor routes. A shared DTO artifact or full server scaffold is unnecessary. `jvm-ktor` is a Ktor client, not a Ktor server generator. Do not apply client serialization flags unchanged to `kotlin-server` options `ktor`/`ktor2`. [Kotlin generator](https://openapi-generator.tech/docs/generators/kotlin/), [server generator](https://openapi-generator.tech/docs/generators/kotlin-server/), [models-only](https://openapi-generator.tech/docs/customization/#selective-generation)

The shared client model template in the pinned research source `v7.16.0` has an `@Serializable` branch, and the Retrofit template imports the official kotlinx converter. This does not recommend the latest version or confirm successful generation/compilation. [model template](https://github.com/OpenAPITools/openapi-generator/blob/v7.16.0/modules/openapi-generator/src/main/resources/kotlin-client/data_class.mustache), [Retrofit template](https://github.com/OpenAPITools/openapi-generator/blob/v7.16.0/modules/openapi-generator/src/main/resources/kotlin-client/libraries/jvm-retrofit2/infrastructure/ApiClient.kt.mustache)

The current options table lists `generateOneOfAnyOfWrappers` support for Retrofit2 + Gson or kotlinx_serialization, while the generic Feature Set marks `oneOf` unsupported. Do not conclude that every combination is supported or that none is. Check optional/nullable/default behavior, unknown enums, `additionalProperties`, arbitrary JSON, and partial decoding in actual output from a pinned generator, template, and schema. Generating `@Serializable` does not imply runtime validation of every schema constraint. [official options and feature table](https://openapi-generator.tech/docs/generators/kotlin/)

## 3. Schema evolution and version dimensions

| Item | Management rule |
| --- | --- |
| Field number / reserved | Do not change or reuse existing numbers. Leave deleted numbers and names as `reserved`. Binary depends on numbers; JSON also depends on names. [Proto3 changes](https://protobuf.dev/programming-guides/proto3/#updating) |
| Presence | Implicit scalars cannot distinguish absence from a default value. Use explicit presence such as `optional` when that distinction is required. Maps/repeated fields do not have separate presence for absent versus empty values. Validate empty eventId values at runtime. [Presence](https://protobuf.dev/programming-guides/field_presence/) |
| Enum | Use `0` for a default such as `UNSPECIFIED`. Parsing a new enum does not imply understanding its meaning. The SDK handling proposal is to treat unknown ACK enums and UNSPECIFIED as non-success. [Proto3 enum](https://protobuf.dev/programming-guides/proto3/#enum) |
| oneof | Moving existing fields, deleting members, or splitting a oneof can lose values. Old versions may not distinguish an unset value from an unknown new member. Successful parsing of a new value type must not imply successful handling. [Oneof](https://protobuf.dev/programming-guides/proto3/#oneof) |
| `package ...v1` | This is a schema namespace. `java_package` sets the JVM package. A package change does not automatically switch endpoints or installed SDKs. [Packages](https://protobuf.dev/programming-guides/proto3/#packages), [Java package](https://protobuf.dev/reference/java/java-generated/#packages) |
| Artifact SemVer | This is the release dimension for the SDK public API and dependencies. Manage it separately from wire-contract support periods. [SemVer](https://semver.org/) |
| HTTP endpoint version | This is the dimension for routing and API lifetime. The management proposal does not require its number to match schema package or artifact numbers. [Ktor routing](https://ktor.io/docs/server-routing.html) |
| protoc / runtime | Compiler release numbers and Java runtime majors differ. New gencode + an older runtime is prohibited. The general sliding window permits major V gencode with V/V+1 runtimes, with security exceptions. Lite has separate API/ABI stability limits, so the general table alone cannot guarantee a combination. Pin the compiler, generation options, and runtime. [version scheme](https://protobuf.dev/support/version-support/), [runtime guarantees](https://protobuf.dev/support/cross-version-runtime-guarantee/), [lite limits](https://github.com/protocolbuffers/protobuf/blob/v33.0/java/lite.md) |

These version dimensions do not replace one another. Compiling both sides from the same schema and verifying communication between a deployed old SDK and a new server are different checks.

## 4. Buf local CI and compatibility matrix

`buf breaking` accepts a Git ref, directory, archive, or schema image as a baseline. A cloud registry or BSR account is not required. The official example is `buf breaking --against '.git#tag=v1.0.0'`. CI must have that ref. This does not establish that this checkout has a Git repository or that the command was executed. [Buf usage](https://buf.build/docs/breaking/usage/), [CLI inputs](https://buf.build/docs/reference/cli/buf/breaking/)

| Category | Protection scope |
| --- | --- |
| `FILE` | Default. File-level generated source compatibility. |
| `PACKAGE` | Package-level generated source compatibility. |
| `WIRE_JSON` | Binary + JSON encoding compatibility. |
| `WIRE` | Binary encoding compatibility. Does not also protect JSON name changes. |

The initial candidate is the default `FILE` with documented exceptions. Keep schema baselines for supported earlier SDK releases as well as comparisons against main. Passing Buf does not prove value validation, DB storage, or ACK semantics. [official categories and rules](https://buf.build/docs/breaking/), [detailed rules](https://buf.build/docs/breaking/rules/)

The following verification plan has not been executed. Tests that share one latest DTO across both sides cannot replace it.

| SDK → Server | Request and storage checks | Response and SDK checks |
| --- | --- | --- |
| Old → Old | Retain the published release baseline | Existing ACK and queue handling |
| Old → New | Storage works without new fields. New validation does not reject previously valid values. | Retain result meanings understood by the old SDK |
| New → Old | Check unknown-field parsing separately from DB storage. Check that a success ACK is not sent while new information is missing. | Fallback for ACKs without new fields. Unknown enums and missing results must not cause deletion. |
| New → New | New metadata and properties survive through the DB and Dashboard | Partial acceptance, duplicates, and retry after a lost response |

Proposed rollout order: first deploy the server so it stores the meaning of old/new requests and returns ACKs understood by old SDKs. Then allow new SDKs to send. Verify that meaning survives rollback to an old server after receiving new payloads. Unknown binary field preservation does not protect mappers that copy only known fields into the DB, or JSON conversions. [unknown field loss conditions](https://protobuf.dev/programming-guides/proto3/#unknowns)

Include the offline queue in the matrix. Check the path where a new SDK reads an old payload and sends it to a new server, Room migrations, absent/default fields, and validation. Whether to store original bytes or re-encode versioned records remains undecided. Retain event IDs, installation IDs, and content from collection time. [Q20-Q24](spec.md), [ADR-0001](../../docs/adr/0001-installation-scoped-identity.md)

## 5. Dynamic properties, numbers, and null

| Representation | Benefits and limits |
| --- | --- |
| `Struct` / `Value` | Represents objects, arrays, strings, booleans, null, and numbers. `number_value` is a double. An absent key differs from explicit `null_value`, and an unset kind is not a valid null. [struct.proto v33.0](https://github.com/protocolbuffers/protobuf/blob/v33.0/src/google/protobuf/struct.proto) |
| Homogeneous map | `map<string,string>` or `map<string,int64>` has one value type. It cannot directly represent heterogeneous values, objects, arrays, or null. Converting everything to strings changes value semantics. [Map](https://protobuf.dev/programming-guides/proto3/#maps) |
| Custom oneof | Can define string, bool, int64, double, null, and object/list wrappers when needed. Map/repeated fields cannot go directly into a oneof. Exact integer classification is possible, but the project owns mapping, validation, and evolution rules. Do not add recursion when nesting is unnecessary. [Oneof](https://protobuf.dev/programming-guides/proto3/#oneof) |

Double cannot preserve every consecutive integer beyond `2^53`. The interoperable integer range for general JSON is `±(2^53−1)`. ProtoJSON's int64 string mapping does not turn a `Struct` double into int64. Converting string int64 to JS `Number` in the Dashboard reintroduces precision loss. Decide how to represent large IDs and exact decimals first. [RFC 8259 §6](https://www.rfc-editor.org/rfc/rfc8259#section-6), [ProtoJSON mapping](https://protobuf.dev/programming-guides/json/#representation-of-each-type)

For ordinary ProtoJSON fields, `null` generally means unset; `NullValue` represents explicit null. Allowed value types, depth, size, finite-number rules, numeric equality, and null/absence remain undecided. Calling `Struct` the simplest choice requires accepting double semantics. Do not narrow Q14's general properties into required shopping-specific fields. [null rules](https://protobuf.dev/programming-guides/json/#null-values), [Q14](spec.md)

## 6. Runtime validation and batch failure boundaries

Successful decoding does not prove that eventId, installation ID, name, time, property limits, or content conflicts are valid. Validate at trust boundaries separately from generated types. [Proto3 defaults](https://protobuf.dev/programming-guides/proto3/#default)

Q22 applies to a batch that can be parsed. After parsing succeeds, permanently reject semantic errors per event. A malformed child, truncated length, or damaged binary body can cause whole-batch parsing failure. Automatic recovery of valid children or partial ACKs is not guaranteed. A new repeated-bytes envelope is unnecessary. With JSON, a child type error can also fail decoding of an entire generated batch. Define the required individual-decoding boundary. [binary length structure](https://protobuf.dev/programming-guides/encoding/#length-types), [MessageLite parsing](https://github.com/protocolbuffers/protobuf/blob/v33.0/java/core/src/main/java/com/google/protobuf/MessageLite.java), [JSON tree parsing](https://kotlinlang.org/api/kotlinx.serialization/kotlinx-serialization-json/kotlinx.serialization.json/-json/parse-to-json-element.html)

Proposed project application:

- Under Q20, collection succeeds when local durable storage completes. Encoding completion is separate.
- Under Q21, respond with successful receipt after DB commit. Only clearly understood accepted/duplicate results justify deletion. Unknown ACK enums, missing results, and ambiguous results are not success. Retain those events.
- Stop automatic retries only for clear permanent rejection. Isolation, diagnostics, and retry termination for malformed request-level errors require a separate adoption check.
- Associating ID errors through request indexes is an existing proposal. Use the fixed list from request time, not the current queue order.
- Q23's limited diagnostics retain only event ID, error code, and time. Do not retain raw properties. Record diagnostics and remove queue entries in one transaction.

The detailed result names and index mechanism are not a finalized contract. Evidence: [Q20-Q24](spec.md), [existing idempotency review](idempotency-design-review.md)

Do not use Protobuf bytes or their hashes as the basis for content equality. Deterministic serialization is not canonical. For the existing parsed fixed-field comparison, ignore object key order, retain array order, and define number/null/absence rules. Review whether new semantic metadata must enter both DB storage and duplicate comparison. The possibility that a wire-compatible change alters duplicate decisions is a project inference. [official non-canonical serialization explanation](https://protobuf.dev/programming-guides/serialization-not-canonical/), [existing comparison proposal](idempotency-design-review.md)

## 7. AAR / Maven distribution

One artifact must own generated classes in a consumer app's dependency graph. Including the same JVM package/classes in both an AAR and a protocol JAR, or including full/lite generated artifacts for the same schema together, risks duplicate classes. Separate server and Android processes differ from duplication within one Android graph. Use an SDK-specific JVM namespace and first determine whether generated types need public API exposure. [Java package](https://protobuf.dev/reference/java/java-generated/#packages), [Android duplicate class](https://developer.android.com/build/dependencies#duplicate_classes)

Check `protobuf-java` and `protobuf-javalite` for overlapping-class conflicts. The researched Retrofit **3.0.0** converter accepts `MessageLite`, but its build uses **full `protobuf-java:3.25.7`**. Adding only the converter to a lite app cannot be assumed safe. Excluding transitive full, substituting compatible lite, or using a small dedicated converter are candidates for verification. None was configured during this research. [Converter build](https://github.com/lysine-dev/retrofit/blob/3.0.0/retrofit-converters/protobuf/build.gradle), [dependency catalog](https://github.com/lysine-dev/retrofit/blob/3.0.0/gradle/libs.versions.toml), [lite cautions](https://github.com/protocolbuffers/protobuf/blob/v33.0/java/lite.md)

Include required artifacts, POMs, and any published Gradle Module Metadata in the Maven ZIP. Copying only the AAR omits transitive dependency information. Decide whether external dependencies are included in the ZIP or resolved from a repository. In a separate consumer app, verify coordinate-based installation, runtime resolution with other SDKs, duplicate classes, R8 release builds, and HTTP communication. Actual published POMs and consumer apps were not verified. [Android publishing](https://developer.android.com/build/publish-library/upload-library), [Gradle Maven Publish](https://docs.gradle.org/current/userguide/publishing_maven.html), [Q17/Q24](spec.md)

## 8. Researcher's application proposal and unresolved adoption checks

The researcher compared Q20 through Q32, ADR-0001, the idempotency review, and official Kotlin, ProtoJSON, Ktor, OpenAPI, and runtime documentation. The report also incorporates missing unknown-field storage, unknown ACKs, and batch parsing boundaries raised by the main agent. Q32 remains undecided. Protobuf offers code generation and field-number-based evolution, but there is no evidence that overall integration is simpler than the current agreement.

The minimum generation scope is a shared event envelope and batch requests/results. Do not couple Room entities, jOOQ records, or the public `track()` API to transport DTOs. Adopting Protobuf requires renewed agreement on the Q29 serializer and Q31 JSON transport direction. gRPC, a cloud registry, and a shared DTO distribution artifact are outside the minimum candidate.

| Check before adoption | Unresolved items |
| --- | --- |
| Property contract | Allowed types, nesting, precision, and null/absence. Whether Struct double is acceptable or a custom value is needed. |
| Generation and integration | Pin compiler/generator/runtime. Verify actual output, serializers, converters, presence, and unknown fields/enums. |
| Batch and ACK | Malformed whole-batch boundaries, semantic partial rejection, retention on unknown ACKs, and request-level error policy. |
| Support lifetime and rollout | Supported SDK releases, queue lifetime/migration, server-first rollout and rollback, and actual request/response/DB matrix. |
| Distribution | Generated-class ownership, runtime conflicts, POM/metadata, and separate consumer app installation, R8, and communication. |

The next decision should test whether both candidates support dynamic properties and partial decoding. If model generation cannot meet those needs, JSON schema/fixtures + handwritten DTOs are a smaller alternative. Do not call that alternative compile-time contract enforcement. No ranking of implementation time, APK size, or performance is made.

The research document remains floating. Sources at `v33.0`, `3.0.0`, `v7.16.0`, and `v0.9.5` are evidence for those versions, not adopted versions or recommendations for the latest release. Some guessed URLs failed to load and were replaced with actual official documentation/source. Access failures were not treated as evidence of unsupported features. Files outside this report, including `discovery.md`, were not changed.
