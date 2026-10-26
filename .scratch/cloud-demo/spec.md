# SignalDock cloud demo spec

Status: design agreed on 2026-10-25 (grilling, decisions D1 to D12). Tickets 01-07 are merged (PRs 207, 210-212, 214, 216, 219). The cloud steps ran on 2026-10-25 UTC: `demo-v0.1.0` failed on a Flyway connect timeout (fixed in PR 221), and `demo-v0.1.1` deployed. Open follow-ups are issues 08 to 14 (`.scratch/cloud-demo/issues/`).

## 1. Goal and scope

Run the server and the Dashboard on a public URL as a portfolio demo. It is not a product and has no real users.

- Live URL: `https://signaldock.stack-line.dev`, until the GCP free-trial credit ends on 2027-01-16. Then tear it down. README keeps screenshots and a short video.
- Readers see the Dashboard (basic auth) and the README. They do not send events.
- Out of scope: real users, high availability, Kubernetes, managed cloud services, push-based CI deploys, server code for rate limits, key tables, TTL, or login.

## 2. Decisions

Each decision goes into an ADR in PR 1 (section 5). ADR-0028 "Revisit when" is now true; the new ADR replaces its Decision table row by row (D2, D3, D9, D1).

| ID | Decision | Rejected alternatives (reason) |
| --- | --- | --- |
| D1 | The Dashboard is behind host nginx HTTP basic auth. No app login. Credentials go only to people who ask. | App login (server code for a demo) |
| D2 | Abuse control is at host nginx: per-IP `limit_req` and `limit_conn`, `limit_req_status 429`, `client_max_body_size`, an `error_page` that adds `Retry-After` and a JSON body. The server 503 load shedding stays. | In-app 429 (revisit when a deployment has no proxy in front or needs per-key limits); Caddy (rate limit needs a third-party module) |
| D3 | The demo DB is reset every day at 04:00 Asia/Seoul and seeded again. A disk check is the backstop. No server TTL. | Server TTL or delete API (spec section 5 keeps events until deletion or DB reset) |
| D4 | `scripts/demo-seed.py` makes the demo data: deterministic (fixed RNG seed), reads `tracking-plan/plan.json`, timestamps relative to now (last 7 days, more recent events), includes plan-violating events and client reports, posts to `POST /v1/events:batch` on `127.0.0.1` (not through host nginx). | Static SQL dump (timestamps get old) |
| D5 | Host: one GCP Compute Engine VM, `asia-northeast3`, `e2-medium` (2 vCPU, 4 GB), 30 GB `pd-balanced` disk, the existing docker-compose stack. | Managed containers (Cloud Run + Cloud SQL, ECS + RDS: about 3 to 4 times the work, $15 to $90 a month, D2 and D3 redesign); PaaS; Azure (GCP is cheaper in Seoul, and the Azure credit is unclear); k3s or Argo CD on one node (RAM, attack surface, canary has no signal with one replica and no traffic) |
| D6 | Terraform manages the VM, firewall (80 and 443; 22 only from the IAP range), static IP, and the Cloudflare A record. State is local. The VM has no service account. | Console clicks (no teardown record); Terraform for budgets (needs billing admin) |
| D7 | Teardown on 2027-01-16: `terraform destroy` (removes the DNS record too), then the GitHub cleanup in the runbook. No paid upgrade. | Paid upgrade (about $37 a month); smaller VM (2 GB is too small for PostgreSQL and the JVM) |
| D8 | Host name `signaldock.stack-line.dev`. Cloudflare DNS only (proxy off), so host nginx sees the client IP. TLS from Let's Encrypt. The root domain stays as it is. | `sslip.io` (shared rate limits); Cloudflare proxy (breaks D2 per-IP limits) |
| D9 | Pull-based tag deploy. A `demo-vX.Y.Z` tag on `main` makes CI build the server and Dashboard images and push them to public GHCR with `GITHUB_TOKEN` only. A systemd timer on the VM fetches tags, checks the tag is on `main`, pulls the images, runs `docker compose up -d`, checks `/ready`, and rolls back on failure. GitHub holds no cloud credentials. A tag ruleset lets only the admin create, move, or delete `demo-v*` tags. | GitHub OIDC + Workload Identity Federation + IAP SSH (cloud credentials in GitHub, IAM state that review cannot see); SSH key secret (long-lived key, port 22 open); build on the VM (RAM, time); required-reviewer environment (no value for one owner) |
| D10 | Cost: stay on the free trial (billing stops when the credit ends). Budget alerts at 50 % and 80 % of the credit, made by hand. | Paid account (a traffic flood becomes a bill) |
| D11 | Access log: host nginx JSON log (no query string, no `Authorization`), kept 7 days; a GoAccess static report behind basic auth, with IP anonymization. It is an access log, not an audit trail: the keys are shared static keys, so the IP is the only caller identity. Keys: the collection key, query key, DB password, and htpasswd live in `/opt/signaldock/.env` (mode 600), made with `openssl rand`, copied once over IAP SSH, never in git, Terraform, or instance metadata. The collection key is not published. Rotation is by hand (runbook). Accepted risk: one key of each kind, no zero-downtime rotation, no record of who read a key. | Secret Manager (needs a service account, against D6); sops + age (the key is on the VM anyway); hashed key table (one caller only); OTel Collector, Cloud Logging, Loki (credentials, service account, or RAM) |
| D12 | Hardening: non-root containers, `cap_drop: ALL`, `no-new-privileges`, `read_only` with tmpfs, memory, pids, and CPU limits; PostgreSQL on an `internal: true` network with the server only; a `DOCKER-USER` rule that drops container traffic to `169.254.169.254` (kept by a systemd unit); JVM `-XX:MaxRAMPercentage`; `unattended-upgrades`; a Trivy image scan in CI; an external uptime check with email alerts; fail2ban on basic-auth failures; one `docker-bench-security` run. | Falco (RAM); rootless Docker, userns-remap (friction for 12 weeks); cosign verify on the VM (one more moving part) |

## 3. Components

| Path | Content |
| --- | --- |
| `infra/terraform/` | GCP VM (no `service_account` block), firewall, static IP, Cloudflare record, cloud-init |
| `infra/compose.demo.yml` | Override: GHCR images by `DEMO_TAG`, D12 container settings. Local `docker-compose.yml` changes only in the Dashboard image (`nginxinc/nginx-unprivileged`) and container port (80 to 8080, host port unchanged), because the unprivileged image cannot bind port 80. |
| `infra/nginx/` | Host nginx: TLS, D2 limits, D1 basic auth, D11 log format, `/deployed.txt` without auth |
| `infra/vm/` | `deploy.sh` and its timer, the reset script and its timer, the `DOCKER-USER` unit, GoAccess timer, fail2ban jail |
| `.github/workflows/demo-deploy.yml` | D9 image build and push, wait for `/deployed.txt` |
| `scripts/demo-seed.py` | D4, with a test |
| `docs/runbook-demo.md` | Setup, manual steps, key rotation, incident steps, teardown |

`docs/design-index.md` gets a section for `infra/` (repo rule).

## 4. Manual steps (owner only)

The agent does not handle tokens or keys.

1. GCP project, Compute Engine API, budget alerts (D10).
2. A Cloudflare API token for one zone, in a local environment variable.
3. `terraform apply`.
4. Make `.env` and htpasswd, copy them over IAP SSH.
5. Create the tag ruleset. After the first image push, set both GHCR packages to Public (this cannot be undone; the images hold no secrets because `.dockerignore` excludes `.env`).
6. Create the uptime check and its email alert.
7. On 2027-01-16: the teardown steps in the runbook.

## 5. PR plan

One purpose per PR (AGENTS.md rule 3). Each PR states its verification commands and results.

1. Docs: the ADR(s) for D1 to D12 that replace ADR-0028; `threat-model.md`, `SECURITY.md`, `README.md` and `README.ko.md` limitations, `docs/design-index.md`.
2. `scripts/demo-seed.py` and its test.
3. Container hardening: `infra/compose.demo.yml`, `nginx-unprivileged` for the Dashboard, JVM memory setting, internal DB network.
4. Terraform, cloud-init, host nginx, `DOCKER-USER` unit, `unattended-upgrades`.
5. Tag deploy workflow, VM deploy and reset timers, runbook.
6. Checks in `scripts/check-repo-rules.sh` and rows in AGENTS.md: actions pinned by full SHA; no `pull_request_target` or `workflow_run`; top-level `permissions` in every workflow; `demo-deploy.yml` runs only on `demo-v*` tag pushes; `demo-deploy.yml` has no `id-token` and no `secrets.`.
7. Detection and checks: Trivy in CI, uptime check, fail2ban, `docker-bench-security` run.

## 6. Verification

- Local: `docker compose -f docker-compose.yml -f infra/compose.demo.yml config`, the stack starts with D12 settings, `/ready` is 200, the seed test passes.
- Cloud, after PR 4 and 5: `terraform plan` shows no service account on the VM; `curl` from a container to `169.254.169.254` times out; `docker ps` shows no `0.0.0.0:` ports; requests over the limit get 429 with `Retry-After`; the Dashboard needs basic auth; a `demo-v*` tag deploys and a broken tag rolls back; the reset timer runs; `docker stats` RAM is recorded.
- Record results in the PR descriptions. A step that did not run is stated as not run.

## 7. Facts to confirm during implementation

These came from research agents and are not verified:

- GCP prices (third-party source) and whether the free-trial credit covers Compute Engine ("applies to specific usage" in the console).
- Leaving out the Terraform `service_account` block attaches no service account.
- The `DOCKER-USER` rule must match compose bridges (no `-i docker0`), and needs IPv6 and reboot handling.
- `nginx-unprivileged` user and port; JVM with `noexec` on `/tmp`; compose `internal: true` and `pids_limit`.
- Flyway behavior on rollback after a newer migration.
- GoAccess options for the nginx JSON format; nginx `$limit_req_status`.
- Stack RAM on `e2-medium`.

## 8. Revisit when

- A second operator or client, or per-caller or revocable keys: hashed key table, in-app limits.
- A service account becomes needed: one per workload, narrow roles, no key files, calls from a host service; then Secret Manager. Calls to AWS or Azure use identity federation with the VM's ID token, not access keys.
- The demo lives past 2027-01-16, has more than one instance, or Kubernetes becomes a portfolio goal: managed containers (Cloud Run) or k3s with plain manifests.
- Logs must be searched by several people: OTel Collector to a hosted backend.
