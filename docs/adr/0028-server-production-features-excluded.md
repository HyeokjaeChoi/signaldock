---
status: accepted
---

# Server production features excluded for local-only operation (issue 48, spec Q157)

The user decided (Q157, 2026-10-15) that the server is tested only on the local machine, with OrbStack (local Docker). It is not operated on the public internet. This ADR records the production features that the server leaves out on purpose. They are not forgotten.

## Context

- The spec scopes the first release to "one local project" with a local Dashboard and no login (spec section 1). It excludes public internet operation (spec section 1, scope list).
- The portfolio goal is to show an SDK and a data processing server that run and are measured locally.

## Decision

The server does not have these features. Each one has an existing source.

| Feature left out | Source |
|---|---|
| Per-key or per-IP rate limit, `429` responses, connection limits. The `503` load shedding stays global. | Q157; Q124 (the `503` load shedding is global) |
| Access log with caller identity, audit trail | Q157 |
| Minimum key length, lockout, rate limit on failed key attempts | Q131 (keys are non-blank, no length rule); Q128 (no key table, no rotation) |
| Event TTL, retention job, delete function | Spec section 5 (keep events until explicit deletion or DB reset); spec queue TTL: "None in the first release" |
| Dashboard login | Spec section 1 ("local Dashboard without login"); Q128 (the Dashboard holds the query key) |

## Consequences

- If someone exposes the server to the public internet, these things break or become unsafe:
  - Anyone who can reach the port can send unlimited requests. One caller can use all capacity.
  - A weak key can be guessed with no limit and no lockout.
  - The Dashboard shows all events to anyone who can open it.
  - There is no automatic TTL and no application delete function. Events stay until an explicit deletion or a database reset (spec section 5). The database grows with no limit until then.
  - There is no record of who called the server.
- Before any non-local deployment, build every row of the Decision table first:
  - rate limits per key and per IP with `429` responses, and connection limits;
  - an access log with caller identity and an audit trail;
  - a key policy: minimum length, rotation, lockout, and a rate limit on failed key attempts;
  - event TTL, a retention job, and a delete function;
  - Dashboard login.
- Gap: none of these features is tested, because none exists.

## Revisit when

Revisit this ADR when any deployment is not on the local machine (for example a shared host, a cloud VM, or a public URL).
