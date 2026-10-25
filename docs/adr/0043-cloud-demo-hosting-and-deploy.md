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
- Gap: nothing in this ADR is verified on a cloud host. Implementation is in tickets 03 to 07 of `.scratch/cloud-demo/issues/`.

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
- Public GHCR packages cannot go back to private without effort. The images hold no secrets because `.dockerignore` excludes `.env`.
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
