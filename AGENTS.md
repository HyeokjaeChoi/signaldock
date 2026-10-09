# SignalDock

## Project purpose

Build a mobile event collection SDK and a data processing server in one monorepo.
Use the project as a portfolio for the Datarize Mobile SDK Engineer role.

## Communication

Explain answers to the user in Korean. Keep technical terms and proper names in their original language.
Use short, clear sentences. State the conclusion first and ask one question at a time.
When writing English, follow the simple, clear language principles of ASD-STE100.

## Research models

When the user requests research, delegate source research to an available low-cost model.
Model names mentioned by the user are examples, not fixed requirements.
The main agent defines the question, reviews the findings, and applies them to the design.
If one model is unavailable, choose another suitable low-cost model.

## Implementation models

Prefer a suitable available low-cost model for product code, tests, and build configuration.
Model names mentioned by the user are examples. Do not require a fixed model or approval for each model choice.
The main agent splits the work, tracks progress, and reviews results.
If one example model fails, try another suitable model before stopping implementation.

## Agent skills

### Issue tracker

Keep issues and specs in local Markdown files.
Before creating or reading an issue, read `docs/agents/issue-tracker.md`.

### Triage labels

Use the five default status names.
Before evaluating or changing an issue's status, read `docs/agents/triage-labels.md`.

### Domain docs

Start with one domain context.
Before exploring code or designing the domain, read `docs/agents/domain.md`.

## Code review

Review against these project invariants, not generic best practices. When a change contradicts an invariant, flag it. When unsure whether a generic concern applies here, check the spec and ADRs first — several "obvious" improvements are deliberately rejected in this project.

### SDK delivery correctness (highest priority)
- `track()` success means the event is durably stored on-device. It is NOT server receipt. Never accept code that treats HTTP success as collection success or vice versa.
- Delete a queue entry only after an unambiguous successful-receipt ACK. On timeout, ambiguous, unparseable, or contradictory responses: apply nothing, preserve the whole batch, back off. Never delete based on failure counts or timeouts alone.
- Validate the full ACK against the immutable request-time list before applying: unknown statuses, duplicate indexes, ID mismatches, or omissions are NOT success.
- Retries reuse the same eventIds. Never generate a new eventId on retry, and never re-track solely because the caller's coroutine was cancelled.
- Propagate `CancellationException`; do not convert it into a normal failure result. Events already committed stay queued and send later.
- Deduplication is eventId plus content comparison (name, installationId, occurredAt, sdkVersion, properties). Same ID with different content is a conflict: preserve the existing row, permanently reject the newcomer. Never describe delivery as exactly-once.

### Input validation
- Properties are flat: strings, numbers, and booleans only. No nested objects, arrays, or nulls. Integers must be within ±(2^53−1); NaN and Infinity are errors.
- Reject, do not silently fix: duplicate DSL keys, empty or whitespace-padded names and keys (no auto-trim), NUL characters or lone surrogates. No Unicode normalization; compare code-point sequences exactly.

### Queue and scheduling
- Limits: 16 KiB per event, 256 KiB and 50 events per batch, 10,000 events and 16 MiB per queue, measured as actual UTF-8 JSON bytes. On overflow, preserve existing events and return QueueFull for new calls.
- Foreground and Worker share one sender and one lock. Never hold a DB transaction open across an HTTP call. Release the lock after each batch.
- Backoff: 10 second base doubling to a 5 minute cap, Full Jitter, one persisted next-eligible time shared by foreground and Worker. Server Retry-After wins. Reset the failure count only after a valid whole ACK is applied.

### Identity and privacy
- Installation ID is UUIDv4, generated once per installation and stored backup-excluded. It survives restarts and updates; data clearing, reinstallation, or restore to a new device starts fresh and may lose unsent events. Queue, diagnostics, and retry state are backup-excluded too. Never change the host app's backup settings.
- Logs default to WARN and ERROR. Never log event content, event names, installation IDs, credentials, or request bodies.

### Contract and codegen
- `openapi/contract-v1.yaml` is the source of truth. Generated code is committed but never hand-edited: review the contract and the templates for semantic changes, not generated diffs.
- Request schemas use `additionalProperties: false`; free-form keys inside `properties` are exempt.
- The `SignalDock-Contract-Version` header is required. A missing header rejects the whole request with HTTP 400 and stores nothing. Never default a missing header to contract 1.
- Compatibility checks (oasdiff) must compare against a checked-in prior baseline, never the contract against itself.

### Server receipt
- Enforce eventId uniqueness with a DB constraint, not lookup-then-insert. Insert valid items in a transaction and return item-level results only after commit.
- Times are UTC RFC 3339 with millisecond precision. Accept numeric offsets as the same instant; reject local times without offsets.

### Do not suggest
Shared Kotlin DTO artifacts, a DI framework in the SDK, Redis for idempotency, aggregate tables or counters, iOS or Flutter SDKs, Maven Central publication, automatic events or user identify, or trimming and normalizing names and keys.

### Decided review rules

These settle recurring judgment calls once, so reviewers stop re-litigating them and the user is not asked again.

- Toolchain absence: verification scripts fail with an explicit setup message when a pinned tool (JDK, generator) is missing. Never silently fall back to whatever is on PATH. Silent fallback makes results environment-dependent and defeats the pinned toolchain.
- Lockfiles are committed. Verification runs `npm ci` (not a bare `npm run`) so the pinned TypeScript version is actually used.
- Every repo script that CI or verification invokes directly is committed executable (mode 100755). A clean checkout must run them without a manual chmod.
- Contract samples must execute assertions, not just typecheck. A sample that compiles but never runs cannot catch conversion regressions.
- Never label a step "verified" without the actual run. If an environment could not run a tool, state that instead of claiming verification.
