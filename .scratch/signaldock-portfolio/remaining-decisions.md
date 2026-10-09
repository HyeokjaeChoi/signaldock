# Remaining decisions and verification through Q127

2026-10-09. This list reflects spec.md, ADRs, and agreements through Q127. It does not guarantee a question count or completion time. It does not reduce existing agreements or add features. Performance design was delegated and finalized in performance-plan.md. Execution has not started.

## Areas requiring user judgment

| Area | Remaining judgment | Established boundary |
| --- | --- | --- |
| Dashboard query UX: delegation complete | At the user's direction, the agent set defaults based on vendor SDK verification flows. No further selection questions | See dashboard-verification-defaults.md. Verify SDK results and keep the agreed queries/aggregates |
| SDK initialization and diagnostics: main choices complete | Verify API signatures and execution boundaries for Q110 through Q118 during implementation | Failure results, instance available after success, explicit retry, shared concurrent work, cancellation of only the caller's wait, default Logcat WARN/ERROR, and level fixed at initialization |
| Transport and overload behavior in normal operation | Policy agreed. Verify exact values and driver cancellation integration during implementation | Q119 through Q121 set SDK timeout defaults to 30/10/10/10 seconds with initialization overrides. Q122 permits internal recovery and limited status follow-up, uses the final result for SDK backoff, and prioritizes minimal logs. Vendor research complete. Q123 prohibits automatic redirects. Q124 sets a bounded wait before DB work, then 503 + positive Retry-After. Q125 agrees on a total time budget, lock/statement limits, rollback, and SDK retry responsibility. The benchmark profile is separate |
| Distribution naming and deliverables | Coordinates agreed: local.signaldock:sdk-android:0.1.0, package local.signaldock. The evaluation flow was reviewed in final-review.md | Maven ZIP, independent consumer app, local Compose, README, and verification video are agreed. No new public publication scope |
| Final spec reconciliation | Q127 full review complete. Proceed with implementation and verification in issues/ | Individual agreements do not replace the full final review. Do not reduce feature scope without authorization |

One area may require several questions. Do not turn questions that schema or implementation can answer into preference questions. Do not create false alternatives that differ only in numbers or method names. At the user's direction, the discussion resumed with SDK initialization and diagnostics and continued through Q127. Dashboard defaults were finalized through separate delegation.

## Items for agent research and implementation verification

- Exact compatible toolchain and dependency versions, compile/target SDK, OpenAPI generator and oasdiff settings, and pinned versions.
- Consistent contracts/fixtures for the properties scalar union, optional/null, numeric precision, RFC 3339 parser boundaries, and Unicode whitespace set. Return to the user only if existing semantics must change.
- Consistency between OpenAPI error schemas, error codes, HTTP classification, and the current ACK/contract-error agreements. Explicit validation of duplicate headers and invalid values; header forwarding/CORS for the browser development path.
- SDK public API signatures, internal packages, repository directory details, and server/demo assembly. Propose changes outside the existing single SDK module and manual DI scope separately.
- Temporary PostgreSQL/Flyway/jOOQ generation, generated file-set/content checks, merged-candidate CI execution, and check integration that matches the actual Git hosting features.
- Agreed runtime checks such as Room backup sidecars, Worker scheduling races, cancellation/commit boundaries, and the R8 consumer app.
- How timeout implementation actually interacts with JDBC cancellation/commit. Confirm public behavior changes in the normal-operation area above.

These items are not already implemented. Perform code generation and build checks during implementation after the final spec review.

## Delegated performance work requiring no further questions

The load, pool candidates, warm-up, repetitions, test timeout settings, metrics, acceptance criteria, and deliverables in performance-plan.md. Evaluate the final pool and resource hypotheses after measurement. There are no results yet, so do not declare an optimum. Performance delegation does not extend to all product UX or public SDK contracts.

## Decisions for future changes

The first release implements only contract 1. Decide the previous-contract support period and migration policy when introducing contract 2. Do not promise indefinite support now or record an end-of-support policy as already decided. Existing exclusions, such as public hosting, multiple projects, iOS/Flutter, and Maven Central, are not current remaining decisions.
