---
status: accepted
---

# A request-level 400 means a contract bug only (issue 49, spec Q158)

The user chose (Q158, option c) to keep the hold rule for a request-level 400 and to make it true: the server must not give a request-level 400 for the content of one event, and a test must catch drift between the SDK and the server.

## Rule

For a correct SDK, a request-level 400 means a contract or configuration bug. The SDK holds the whole queue and rechecks every 30 minutes (ADR-0006 table, "400 | Hold (CONTRACT)"; Q58, Q67, Q105). This is intended and does not change. A content problem of one event is a per-item rejection in a 200 ACK, so one bad event never blocks the queue of other events.

## Decisions

1. **Lone surrogate escapes are per-item.** A lone surrogate escape such as `\ud800` or `\udc00` is valid JSON syntax. `StrictJsonScanner.unicodeEscape` in `server/app/.../RequestParsing.kt` no longer fails the request. It keeps the code unit. The existing per-item text rule (`validUnicode` in `IngestRules.kt`) rejects it: `INVALID_TEXT` for the name and `sdkVersion`, `INVALID_PROPERTIES` for a property key or value. A lone surrogate in `eventId`, `occurredAt`, or `installationId` gives the matching per-item code. A valid pair (`😀`) is unchanged.
2. **A nested or null property value is per-item.** The old limit `MAX_JSON_NESTING_DEPTH = 8` failed the whole request when a property value nested deeper. The scanner now builds its tree only down to depth 3 (root, `events`, one event, `properties`). A container at depth 4 or deeper is a property value or part of an event field. A new method, `skipContainer`, checks its syntax with an explicit stack (no recursion, no tree) and records its span. Memory is bounded by the body size limit, and the call stack is bounded for any input. Raw syntax errors in a nested value (a missing bracket, a trailing comma) still fail the request, as before.
3. **The second parse sees `null` for each skipped container.** `kotlinx.serialization` reads nested JSON with recursion and overflows the stack on deep input (the first test run showed this). So `parseBatch` gives it the source with each skipped span replaced by `null`. The per-event byte size and the duplicate-key checks still use the original source.
4. **The DTO no longer decides property values.** The generated `EventIngest` DTO cannot hold a `null` or a nested property value, so decoding failed and the item got `INVALID_EVENT`. `fieldsOf` now decodes the DTO with an empty `properties` object, and `propertyValuesOf` (which already maps such values to `Unsupported`) decides. Result: a nested object, a nested array, or `null` as a property value is `INVALID_PROPERTIES`, as the rules in `IngestRules.kt` always intended. A `properties` value that is not an object is still `INVALID_EVENT`. A top-level event field with a nested value (for example `"name":[...]`) is still `INVALID_EVENT`.
5. **The whole-request depth limit is replaced, not removed.** The limit was a guard against stack overflow. The guard is now the bounded recursion (depth 4) plus the iterative skip, and the body limit (256 KiB) and event limit (16 KiB) still apply. A test sends 5000 nested levels in one property value.
6. **Drift test with a committed corpus.** The server cannot run the SDK code (an Android library with Robolectric tests), and the SDK cannot run the server parser. So the SDK side writes a corpus and the server side reads it, with the byte-for-byte comparison on the SDK side:
   - `fixtures/sdk-wire/*.json`: request bodies built with the real `track()` path and the real batch assembly (`batchRequestJson`, `buildBatch`) with a fixed installation ID. Cases: minimal event; all property types and boundary numbers (`2^53-1`, `Int.MIN_VALUE`, small and large doubles, `-0.0`); Korean, emoji, astral, escapes, control characters, U+2028; empty and short strings; the largest event (16 KiB exactly); the largest event made of 3-byte, 4-byte, and escaped characters; the most properties in one event; 50 events in a batch; a batch cut at 256 KiB.
   - `WireCorpusTest` (`sdk/sdk-android`, runs in `testDebugUnitTest`) fails when the SDK output differs from the files. To refresh the files after a deliberate change, run it with `UPDATE_SDK_WIRE=1` and review the diff.
   - `SdkWireCorpusTest` (`server/app`, runs in `./gradlew test`) parses each file with the real `parseBatch` and expects no request-level failure and no rejected item.
   Both tests run in CI already (the SDK unit test job and the server job), so no new script step is needed. A change of the SDK serialization or of a server rule that breaks the other side fails one of the two tests.

## Test mechanism

- `IngestValidationTest`: lone surrogate escapes in the name, a property key, and a property value are per-item with a valid sibling and a valid pair; nested values (also 5000 levels) are `INVALID_PROPERTIES`; syntax errors in a nested value stay `MALFORMED_REQUEST`; the old test with 256 KiB of `[` still fails the request.
- `IngestionIntegrationTest` (real PostgreSQL): a batch with one valid event, a lone surrogate, a nested value, and a deep value stores only the valid event; the ACK has one `accepted` and three `rejected`.
- `WireCorpusTest` and `SdkWireCorpusTest`: the drift test above.
- The new server tests were run against the old parser to check that they fail there. The result is in the issue 49 file.

## Gaps

- A client that does not follow the contract (a third-party client or a hand-written request) can still get a request-level 400 for content that the SDK cannot make, for example an unknown top-level field or a missing `contractVersion`. This is intended: those are contract errors.
- Raw JSON syntax errors (including in a nested property value) stay request-level. The server cannot find the end of an item in broken syntax.
- A lone surrogate in `contractVersion` is a request-level error (`CONTRACT_VERSION_UNSUPPORTED`), because the version decides how to read the request.
- The corpus covers the shapes that the SDK can make today. A new SDK feature (a new property type, a new field) needs new cases in `WireCorpusTest`; the test does not find a missing case by itself.
- The corpus holds a 256 KiB file. Git stores it compressed, but each refresh adds a new copy.
