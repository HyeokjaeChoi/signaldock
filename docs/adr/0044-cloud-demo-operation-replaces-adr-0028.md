---
status: accepted
---

# Cloud demo operation controls replace the ADR-0028 rows (cloud-demo spec D1, D2, D3, D4, D11)

[ADR-0028](0028-server-production-features-excluded.md) lists server features left out for local-only operation, and says to revisit when a deployment is not local. [ADR-0043](0043-cloud-demo-hosting-and-deploy.md) is such a deployment (2026-10-25 UTC). This ADR says what covers each ADR-0028 row on the demo. The source is `.scratch/cloud-demo/spec.md`.

## Context

- The demo has one owner and no real users. Readers do not send events.
- The server code does not change. The controls sit at host nginx and on the VM, not in the application.
- `POST /v1/events:batch` and `POST /v1/client-reports` stay reachable on the public URL through host nginx. They need the collection key, which is not published, and the per-IP limits apply (spec D2). The demo is view only (spec section 1, D11): readers do not send events, but the route is not closed.
- Gap: none of these controls is built or verified yet (tickets 02 to 07 of `.scratch/cloud-demo/issues/`). The facts in spec section 7 about `limit_req_status` and GoAccess options are not verified.

## Decision

1. **Dashboard access (D1).** The Dashboard is behind host nginx HTTP basic auth. There is no app login. The credentials go only to people who ask. Host nginx requires basic auth on every route except four: `POST /v1/events:batch` and `POST /v1/client-reports` (to the server; the collection key and per-IP limits apply), `GET /ready` (uptime check), and `GET /deployed.txt` (deploy check; it holds only the tag). All other paths, including every `GET /v1/*` query route and the GoAccess report, go through basic auth before the request reaches the Dashboard container. Host nginx never sends a `GET /v1/*` request to the server or the Dashboard without basic auth.
2. **Abuse control (D2).** Host nginx sets per-IP `limit_req` and `limit_conn`, `limit_req_status 429`, `client_max_body_size`, and an `error_page` that adds `Retry-After` and a JSON body. The server 503 load shedding stays.
3. **Reset (D3).** The demo database is reset every day at 04:00 Asia/Seoul and seeded again. A disk check is the backstop. The server has no TTL.
4. **Demo data (D4).** `scripts/demo-seed.py` makes deterministic data (fixed RNG seed) from `tracking-plan/plan.json`, with timestamps relative to now (last 7 days, more recent events). It includes plan-violating events and client reports. It posts to `POST /v1/events:batch` on `127.0.0.1`, not through host nginx.
5. **Access log and keys (D11).** Host nginx writes a JSON access log without the query string and without `Authorization`. It is kept 7 days. A GoAccess static report sits behind basic auth, with IP anonymization. This is an access log, not an audit trail: the keys are shared static keys, so the IP is the only caller identity. The collection key, query key, DB password, and htpasswd live in `/opt/signaldock/.env` (mode 600), made with `openssl rand`, copied once over IAP SSH, and never in git, Terraform, or instance metadata. The collection key is not published. Rotation is by hand (runbook).
6. **Accepted risk (D11).** One key of each kind. No zero-downtime rotation. No record of who read a key.

### Row by row

| ADR-0028 row | What covers it on the demo | Risk that stays |
| --- | --- | --- |
| Per-key or per-IP rate limit, `429`, connection limits | Host nginx per-IP `limit_req` and `limit_conn` with `429` and `Retry-After` (item 2). The server 503 stays. | No per-key limit. One IP behind NAT shares a limit. A client that skips the host (none is exposed) is not limited. |
| Access log with caller identity, audit trail | Host nginx JSON access log, 7 days, GoAccess report (item 5). | The log is not an audit trail. The IP is the only identity. After 7 days the record is gone. |
| Minimum key length, lockout, rate limit on failed key attempts | Keys are 256-bit random values made with `openssl rand`, so guessing is impractical. The per-IP `limit_req` slows attempts (item 2). | The server has no length rule and no lockout on wrong collection or query keys (server 401). fail2ban does not watch these keys. One key of each kind. No zero-downtime rotation. No record of who read a key. |
| Event TTL, retention job, delete function | Daily reset and seed at 04:00 Asia/Seoul, disk check as backstop (item 3). | The database grows until the next reset. Events from a day are lost at reset. There is still no delete function. |
| Dashboard login | Host nginx basic auth on all routes except the four in item 1. fail2ban bans IPs that fail basic auth (ADR-0045). | A shared credential. No per-user identity. Anyone with the credential reads all demo data. |

## Consequences

- The application stays as in ADR-0028 for all five rows. A deployment without host nginx in front (or one that needs per-key limits) is not covered.
- The demo data is synthetic. Do not put real data in it.
- Tickets 02 to 07 must not claim a control works until the check in spec section 6 ran.

## Rejected alternatives

- App login (server code for a demo).
- In-app 429 (revisit when a deployment has no proxy in front or needs per-key limits).
- Caddy (a rate limit needs a third-party module).
- Server TTL or a delete API (spec section 5 keeps events until deletion or DB reset).
- A static SQL dump as demo data (timestamps get old).
- Secret Manager (needs a service account, against ADR-0043 item 2). sops + age (the key is on the VM anyway). A hashed key table (one caller only).
- OTel Collector, Cloud Logging, Loki (credentials, a service account, or RAM).

## Revisit when

- A second operator or client, or per-caller or revocable keys: a hashed key table and in-app limits.
- A service account becomes necessary: Secret Manager.
- Logs must be searched by several people: an OTel Collector to a hosted backend.
- A deployment has no proxy in front: in-app 429.
