---
status: accepted
---

# SDK contract deprecation warning (issue 50, spec Q159)

The user decided (Q159, 2026-10-16) how long the server supports an old contract version, how it warns about the end, and what the SDK does now. This ADR records the full policy, what is built now, and what is deferred.

## Context

- Contract versions are separate from SDK release versions (ADR-0002, Q65). The SDK sends queued events under their original contract (Q66). An unsupported version gets `CONTRACT_VERSION_UNSUPPORTED`, and the SDK keeps the queue and checks every 30 minutes (Q67, Q58).
- Only contract 1 exists. An SDK that ships today cannot learn about a future end of support, unless it already reads a warning.

## Decision (the Q159 policy)

1. **Support policy.** After a new contract version ships, the server keeps accepting the previous version for at least 12 months AND until its traffic falls below a threshold. Then it answers `CONTRACT_VERSION_UNSUPPORTED` (Q67, unchanged).
2. **Notice.** The server will send the `Deprecation` (RFC 9745) and `Sunset` (RFC 8594) response headers on requests that use a deprecated contract version.
3. **SDK, now.** The SDK reads these headers, writes one WARN per process, and shows the state in `diagnostics()`. It has no end-user UI. It never changes delivery.
4. **Versioning.** The SDK major version changes only when the public API breaks. A contract change alone does not force a public API change.

## Built now (SDK)

- `RetrofitBatchTransport` reads `Deprecation` and `Sunset` from every HTTP response, whatever the status. Reason: the headers cost nothing to read, and a rejected `400 CONTRACT_VERSION_UNSUPPORTED` or a `5xx` can carry them. `Sender.onHttp` passes them to `DeprecationNotice` before the status is handled. The status handling is not changed.
- `DeprecationNotice` parses them:
  - `Sunset`: HTTP-date (RFC 9110 IMF-fixdate). The parser is the one that `Retry-After` uses (`parseHttpDateMillis` in `RetryPolicy.kt`). A date in the past is still valid information.
  - `Deprecation`: a structured field date `@<epoch-seconds>` (RFC 9745). Other values (for example the old `true`) are ignored.
  - One valid header is enough. The other field is `null`. Garbage in either header is ignored. Nothing throws.
- The latest valid values are kept in memory. The first valid notice in a process writes one WARN (`server contract version is deprecated; sunset date: <ISO-8601 or unknown>`). The WARN has no event content, key, or body (Q115). The state is not stored in Room: after a process restart, the next response sets it again and the SDK writes one WARN again. This is "one WARN per process".
- `Diagnostics.contractDeprecation: ContractDeprecation?` with `deprecatedAtEpochMillis` and `sunsetAtEpochMillis`.

### Why a body property on `Diagnostics`

`Diagnostics` is a public `data class`. A new constructor property would change the constructor, `copy`, `copy$default`, and add `componentN`. That breaks binary compatibility for hosts and would force a major version (decision 4). Options:

| Option | Result |
|---|---|
| New constructor property with a default | Breaks the constructor and `copy` signatures (a default does not keep the old JVM signature). Rejected. |
| New accessor on `SignalDock` (for example `deprecation()`) | No break, but the state is not in `diagnostics()`, as Q159 asks. |
| **Property in the class body (`var`, `internal set`)** | Adds only one getter. Constructor, `copy`, `componentN`, `equals`, `hashCode`, and `toString` stay as they were. Chosen. |

Cost of the chosen option: the value is not part of `equals`, `hashCode`, `toString`, or `copy`. A copy made with `copy()` has `null` there. The KDoc says this. `ContractDeprecation` is a plain class (not a `data class`) so that adding a field later does not break the API. The API dump diff is additive (`ContractDeprecation`, `getContractDeprecation`).

## Deferred (built only when contract 2 is made)

- The server sending `Deprecation` and `Sunset` headers.
- Traffic counts per contract version and per collection key, and the threshold from decision 1.
- Any server code or contract (OpenAPI) change. The headers are not in `openapi/` now, so the contract baseline does not change.

## Consequences

- An SDK of this release already understands the warning when the server starts to send it. No SDK update is needed for the warning.
- Delivery results do not depend on the headers (tests compare both).
- The WARN is in Logcat only; the host app can read `diagnostics()` and show its own message. The SDK shows nothing to end users.

## Gaps

- Gap: no server sends the headers yet, so only the SDK side with a fake transport and `MockWebServer` is tested. No end-to-end test with a real server exists.
- Gap: the 12 months and the traffic threshold are policy, not enforced by any code. The threshold value is not chosen.
- Gap: a header with several `Sunset` lines or a comma list is not tested beyond "invalid is ignored".
- Gap: the WARN is once per process, not once per sunset date. If the server later gives another date in the same process, `diagnostics()` shows the new date but there is no second WARN.
- Gap: the demo app and the Dashboard do not show `contractDeprecation`.
- Gap: not run on a device or emulator; JVM (Robolectric) tests only.
