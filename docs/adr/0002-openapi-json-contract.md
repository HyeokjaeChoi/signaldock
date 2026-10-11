---
status: accepted
---

# OpenAPI contract and JSON transport

SignalDock's SDK ingestion API and Dashboard query API use JSON. OpenAPI is the contract source, with generated Kotlin models for the Android SDK and Ktor server and TypeScript models for the Dashboard. The components share the contract instead of publishing a shared Kotlin DTO artifact. Ingestion requests and query responses are separate for their respective roles and reuse common schemas. The user agreed to final option 1 on 2026-10-08.

The comparison considered Protobuf schema-based generation and versioning. OpenAPI tools can also support the current goals of code generation, contract history, and compatibility change checks. Maintaining Protobuf for ingestion alongside JSON for queries would add codecs, generation paths, and representation conversion. There is currently no separate binary transport requirement that justifies this cost, so both APIs use JSON. Kotlin serialization retains the existing kotlinx.serialization choice.

Combine contract change comparisons, regeneration and compilation with a pinned generator, older JSON fixtures, and storage and ACK verification. Successful compilation of generated types alone does not guarantee input validity or compatibility with SDKs already released. SDK release versions, contract document revisions, and OpenAPI syntax versions are separate.

In Q65, the user agreed to select the API contract version through a header, separately from the SDK release version. Incompatible changes increment the contract version. Compatible optional field additions stay within the same version, with the server deployed first to accept them. Detailed schema change history is tracked through OpenAPI/Git release baselines. The first release implements only contract 1. Existing queue contracts follow Q66; unsupported contracts and fields follow Q67; missing headers follow Q105; and the header name follows Q106.

In Q66, the user agreed to store the transport contract version with each queue entry. Existing events retain their original contract after an SDK update, and batches are grouped by version. The sdkVersion and eventId from collection time remain unchanged. New SDKs retain sending and ACK handling for supported older queues. Retirement of older contracts requires a separate policy; this agreement does not mean indefinite support.

In Q67, the user agreed to explicitly reject unsupported contract versions and request contract fields unknown to the server. The SDK preserves the queue, applies recovery checks at 30-minute intervals, and does not downgrade automatically. This prevents the server from discarding original fields while reporting receipt success. Free-form keys inside Properties are exempt from this restriction. This agreement replaces the earlier deferral of the policy for unsupported contracts and fields.

The specific generator, specification version, and diff tool combination will be pinned during later verification. Q33~Q35 established flat string/number/boolean Properties with no null values. Generation, compilation, and communication have not yet been verified.

Evidence: [Protobuf comparison research](../../.scratch/signaldock-portfolio/protobuf-contract-research.md), [Recommendation change and integration review](../../.scratch/signaldock-portfolio/protobuf-design-review.md), [Kotlin generator](https://openapi-generator.tech/docs/generators/kotlin/), [TypeScript generator](https://openapi-generator.tech/docs/generators/typescript-fetch/), [oasdiff](https://github.com/oasdiff/oasdiff).

In Q91, the user agreed to track both OpenAPI and jOOQ generated sources in Git. This replaces Q90's policy of excluding OpenAPI outputs. Review changes to source contracts and generated outputs together. CI regenerates into an empty temporary output location with pinned inputs and tools, then compares the file set and contents. Normal compilation does not overwrite generated outputs. Git merge conflict checks alone cannot verify drift or contract compatibility, so the existing schema diff, fixture, compilation, and integration checks remain required. Details: [Generated code policy review](../../.scratch/signaldock-portfolio/generated-code-policy-review.md). Implementation and execution verification have not yet been performed.

In Q105, the user agreed that an ingestion request without a contract version header must be rejected in full with HTTP 400 and an explicit contract error, with nothing stored. The SDK preserves the queue and follows Q58 recovery checks. A missing header is not automatically interpreted as contract 1. Q106 fixed the header name as `SignalDock-Contract-Version`. Its first-release value is `1`, separate from the event's `sdkVersion`.

In Q107, the user agreed to require the same contract version header for Dashboard business query APIs. A missing header is rejected with HTTP 400, and unsupported versions are not automatically interpreted. Health checks and static files are exempt. The first release implements only contract 1; handlers for older versions are not created automatically. UI recovery behavior for Dashboard contract errors will be decided separately.

## Amendment: contract verification (2026-10-11, PR #9)

Compare the contract with the prior baseline stored in Git (`fixtures/contract/contract-v1.baseline.yaml`). Never compare the contract with itself.

A contract sample must run assertions. A sample that only compiles is not a check.

Mark request schemas `additionalProperties: false`. Free-form keys inside `properties` are exempt.

Reviewers read the contract and the generator templates. The drift check in `scripts/verify-contract.sh` proves that the generated outputs match the contract, so reviewers do not read generated diffs. This replaces the statement in the Q91 paragraph that reviewers read source contracts and generated outputs together.
