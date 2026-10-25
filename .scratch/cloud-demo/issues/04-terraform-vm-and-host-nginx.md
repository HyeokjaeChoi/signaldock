# 04. Terraform, VM setup, and host nginx

Status: ready-for-agent
Type: task
Blocked by: 01, 03

## Scope and completion criteria

Build spec decisions D5, D6, D8, D1, D2, D11 (log part), and the host part of D12.

1. `infra/terraform/`: one `e2-medium` VM in `asia-northeast3`, 30 GB `pd-balanced`, no `service_account` block; static IP; firewall: 80 and 443 from anywhere, 22 only from `35.235.240.0/20` (IAP); Cloudflare A record `signaldock.stack-line.dev`, proxy off. Providers pinned. No secret values in any Terraform file or variable default. Local state; `*.tfstate*` ignored by git.
2. cloud-init: the VM installs the Docker Engine and Compose plugin from Docker's apt repository; `infra/compose.demo.yml` uses `!override`/`!reset`, which need Compose 2.24 or later; verification prints `docker compose version`. Also: install Docker from the official repository, clone the public repo to `/opt/signaldock/src`, install the units from `infra/vm/`, enable `unattended-upgrades`. No secrets in instance metadata.
3. `infra/nginx/`: TLS with Let's Encrypt (certbot, renewal timer); per-IP `limit_req` and `limit_conn` for ingest, `limit_req_status 429`, `error_page` that returns JSON with `Retry-After`; `client_max_body_size` at the server batch limit; basic auth on every route except `POST /v1/events:batch`, `POST /v1/client-reports` (collection key and per-IP limits apply), `GET /ready`, and `GET /deployed.txt` (tag only); the Dashboard, every `GET /v1/*` query route, and the GoAccess report need basic auth; JSON access log without query string or `Authorization`, logrotate 7 days. Proxies to the 127.0.0.1 ports of compose.
4. `infra/vm/`: a systemd unit, ordered after `docker.service`, that adds `DOCKER-USER` rules to drop container traffic to `169.254.169.254` (IPv4 and IPv6 handling as needed; match compose bridges, not only `docker0`).
5. `docs/runbook-demo.md` first part: owner steps 1 to 4 of spec section 4.

Owner steps (not the agent): `terraform apply`, the Cloudflare token, `.env` and htpasswd.

Verification:
- `terraform fmt -check`, `terraform validate`, and `terraform plan` output (plan shows no service account).
- `nginx -t` on the config (in a container is fine).
- After the owner applies: from a container, `curl -m 3 -H 'Metadata-Flavor: Google' http://169.254.169.254/` times out; `docker ps` shows no `0.0.0.0:` ports; over-limit requests get 429 with `Retry-After`; the Dashboard asks for basic auth; `GET /v1/events` (and any other query route) without basic auth gets 401 from host nginx; the four routes without basic auth work; the rules survive a reboot.
