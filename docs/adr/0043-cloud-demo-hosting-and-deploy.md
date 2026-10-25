---
status: accepted
---

# Cloud demo: hosting, DNS, deploy, and teardown (cloud-demo spec D5 to D10)

On 2026-10-25 UTC the owner decided to run the server and the Dashboard on a public URL as a portfolio demo. This ADR records where it runs, how it is deployed, and when it ends. [ADR-0044](0044-cloud-demo-operation-replaces-adr-0028.md) covers the operation controls. [ADR-0045](0045-cloud-demo-host-and-container-hardening.md) covers the hardening. The source is `.scratch/cloud-demo/spec.md` (decisions D1 to D12).

## Context

- The demo is not a product. It has no real users (spec section 1).
- Live URL: `https://signaldock.stack-line.dev`, until the GCP free-trial credit ends on 2027-01-16 UTC. Readers see the Dashboard and the README. They do not send events.
- [ADR-0028](0028-server-production-features-excluded.md) says to revisit it when a deployment is not local. This is such a deployment.
- Out of scope: real users, high availability, Kubernetes, managed cloud services, push-based CI deploys.
- Gap: nothing in this ADR is verified on a cloud host. Implementation is in tickets 02 to 07 of `.scratch/cloud-demo/issues/`.

## Decision

1. **Host (D5).** One GCP Compute Engine VM in `asia-northeast3`, type `e2-medium` (2 vCPU, 4 GB), 30 GB `pd-balanced` disk. It runs the existing Docker Compose stack.
2. **Infrastructure as code (D6).** Terraform manages the VM, the firewall (ports 80 and 443; port 22 only from the IAP range), the static IP, and the Cloudflare A record. State is local. The VM has no service account (no `service_account` block).
3. **Host name and TLS (D8).** `signaldock.stack-line.dev`. Cloudflare DNS only (proxy off), so host nginx sees the client IP. TLS from Let's Encrypt. The root domain does not change.
4. **Tag deploy (D9).** A tag `demo-vX.Y.Z` on `main` makes CI build the server and Dashboard images and push them to public GHCR with `GITHUB_TOKEN` only. A systemd timer on the VM fetches tags, checks that the tag is on `main`, pulls the images, runs `docker compose up -d`, checks `/ready`, and rolls back on failure. GitHub holds no cloud credentials. A tag ruleset lets only the admin create, move, or delete `demo-v*` tags.
5. **Cost (D10).** Stay on the free trial. Billing stops when the credit ends. The owner makes budget alerts at 50 % and 80 % of the credit by hand.
6. **Teardown (D7).** On 2027-01-16 UTC: `terraform destroy` (it removes the DNS record too), then the GitHub cleanup in the runbook (`docs/runbook-demo.md`, planned in ticket 05). No paid upgrade. README keeps screenshots and a short video.
7. **Manual steps.** The owner does the steps that need tokens or keys (spec section 4). The agent does not handle tokens or keys.

## Consequences

- One VM is one point of failure. This is accepted for a demo.
- Making the GHCR packages public cannot be undone (GitHub docs). The images hold no secrets because `.dockerignore` excludes `.env`.
- A deploy needs a tag from the admin. A rollback after a newer Flyway migration is not verified (spec section 7).
- The demo ends on 2027-01-16 UTC. The README limitation must be removed or changed at teardown.
- Gap: the facts in spec section 7 are not verified: GCP prices (third-party source); that the free-trial credit covers Compute Engine; that no `service_account` block means no service account; Flyway rollback behavior; stack RAM on `e2-medium`.

## Rejected alternatives

- Managed containers (Cloud Run + Cloud SQL, ECS + RDS): about 3 to 4 times the work, $15 to $90 a month, and D2 and D3 need a new design.
- PaaS.
- Azure: GCP is cheaper in Seoul, and the Azure credit is unclear.
- k3s or Argo CD on one node: RAM and attack surface; a canary has no signal with one replica and no traffic.
- Console clicks (no teardown record). Terraform for budgets (needs billing admin).
- Paid upgrade (about $37 a month). A smaller VM (2 GB is too small for PostgreSQL and the JVM).
- `sslip.io` (shared rate limits). Cloudflare proxy (it breaks the per-IP limits in ADR-0044).
- GitHub OIDC + Workload Identity Federation + IAP SSH: cloud credentials in GitHub, and IAM state that review cannot see.
- An SSH key secret: a long-lived key and an open port 22.
- Build on the VM (RAM, time). A required-reviewer environment (no value for one owner).
- A paid account (a traffic flood becomes a bill).

## Revisit when

- The demo lives past 2027-01-16 UTC, has more than one instance, or Kubernetes becomes a portfolio goal: managed containers (Cloud Run) or k3s with plain manifests.
- A service account becomes necessary: one per workload, narrow roles, no key files. Calls to AWS or Azure use identity federation with the VM's ID token, not access keys.

## Amendment 2026-10-25 (ticket 04)

- Terraform creates a dedicated VPC `signaldock-demo` (subnet `10.10.0.0/24`). The project's `default` network has `default-allow-ssh` and `default-allow-rdp` open to `0.0.0.0/0`; with it, "22 only from IAP" would be false.
- SSH uses metadata keys through IAP, not OS Login, because the account is personal.
- The first TLS certificate and each renewal use `certbot --standalone` with pre and post hooks that stop and start nginx. nginx cannot start the 443 server before the certificate exists. Cost: a few seconds of downtime about every 60 days.

## Amendment 2026-10-25 (ticket 05)

- Compose files and the tracking plan come from the checked-out tag (they must match the images). The deploy script itself does not: it runs from `/usr/local/sbin`. Trust: the tag must be an ancestor of `origin/main` (checked with the VM's git before any file of the tag is used), and only the owner can push tags.
- A change in `infra/vm` reaches the VM only when the owner runs `install.sh` by hand from `origin/main`.
- Images not pullable yet (CI still running, or packages not Public) is not a failed deploy: no change, retry on the next run. The deploy checks this with plain `docker pull` of the two app images, not `compose pull`: both services also have `build:`, and Compose can treat a missing image of such a service as "build from source".
- Only the newest `demo-vX.Y.Z` is considered. Image cleanup keeps the current and the previous tag (rollback needs it).
- `signaldock-disk-check.timer` runs hourly and resets early at 80 % disk use, with a 6 h guard after the last reset.
- The seed key is read from `.env` with `grep` (the file is never sourced). The seed runs as `nobody`.
- The reset waits on the shared lock (up to 15 min); the deploy skips when it is held.
- Exit codes: 0 for deployed, nothing to do, skipped, or images not yet available; 1 for failed deploy, refused tag, or missing `.env`.
- The deploy timer is `*:0/5`. The units start after `signaldock-docker-user.service`, so the metadata DROP rule exists before the stack starts.
- The Dashboard (`127.0.0.1:5173`) is part of the health check, with the server `/ready`, in both deploy and reset. A tag with a broken Dashboard is marked failed and rolled back.
- Flyway: an older image starts on a newer schema (tested with Flyway 13.10.0: a warning, state `Future`). After a rollback over a migration, the operator runs `signaldock-reset`.
