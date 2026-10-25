# Runbook: cloud demo

Sections 1 to 8: setup by the owner (spec section 4, steps 1 to 4). Sections 9 to 17 (ticket 05): VM scripts, tag ruleset, first tag, deploy, reset, failed deploy, rollback and Flyway, key rotation, teardown. The uptime check is added by ticket 07.

Design: ADR-0043 (hosting), ADR-0044 (nginx, keys), ADR-0045 (hardening). Nothing here has run on a real VM yet. A command that has not run is marked "not run" in the PR description.

Agents do not handle tokens or keys. You run these steps.

## 1. GCP project and budget alerts

The project `signaldock-demo` exists, billing is linked, and the Compute Engine API is on.

1. Log in for `gcloud` and for Terraform (ADC):

   ```bash
   gcloud auth login
   gcloud auth application-default login
   gcloud config set project signaldock-demo
   ```

2. Console, Billing, Budgets & alerts: make a budget with alerts at 50 % and 80 % of the free-trial credit (ADR-0043 item 5). Terraform does not manage budgets.
3. Check the credit covers Compute Engine: Console, Billing, Credits. Record what the page says in the PR description (spec section 7, not verified).

## 2. Cloudflare token

Cloudflare dashboard, My Profile, API Tokens, Create Token. Permission `Zone / DNS / Edit`, zone resources: only `stack-line.dev`. Read the zone ID on the zone Overview page (it is not a secret).

```bash
read -rs CLOUDFLARE_API_TOKEN && export CLOUDFLARE_API_TOKEN   # paste the token, press Enter
export ZONE_ID=<zone id of stack-line.dev>
```

The token stays in this shell. Do not put it in a file, a `-var`, or a shell history line.

## 3. Terraform

Merge the PR with `infra/` first: the VM clones `main` on its first boot and installs `infra/nginx/` and `infra/vm/` from the clone.

```bash
terraform -chdir=infra/terraform init
terraform -chdir=infra/terraform plan -var cloudflare_zone_id="$ZONE_ID"
terraform -chdir=infra/terraform apply -var cloudflare_zone_id="$ZONE_ID"
```

Read the plan: 7 resources (network, subnet, address, two firewall rules, instance, DNS record). The instance must have no `service_account` block, the record `proxied = false`, and port 22 only from `35.235.240.0/20`. The DNS record name is the full name (`signaldock.stack-line.dev`).

After apply, run the plan again: `terraform -chdir=infra/terraform plan -var cloudflare_zone_id="$ZONE_ID"` must show "No changes". Any diff on the DNS record means drift.

State is local (`infra/terraform/terraform.tfstate`, git-ignored). Keep a copy outside the repo: without it `terraform destroy` cannot find the resources.

Check the DNS record:

```bash
terraform -chdir=infra/terraform output
dig +short signaldock.stack-line.dev        # must print the "ip" output
```

## 4. First boot check (IAP SSH)

```bash
gcloud compute ssh signaldock-demo --zone asia-northeast3-a --tunnel-through-iap
```

On the VM (cloud-init takes a few minutes):

```bash
sudo cloud-init status --wait                      # status: done
sudo grep -E 'Docker Compose version' /var/log/cloud-init-output.log   # must be 2.24 or later
systemctl is-active signaldock-docker-user docker  # active, active
sudo iptables -S DOCKER-USER                       # has: -A DOCKER-USER -d 169.254.169.254/32 -j DROP
sudo ls /opt/signaldock/src/infra                  # the clone
```

If `cloud-init status` says `error`, read `/var/log/cloud-init-output.log`. The script stops at the first failing command.

## 5. Keys, `.env`, and htpasswd

Make the files on your computer, in a private directory. Do not commit them and do not paste them into chat or Terraform.

```bash
umask 077
mkdir -p ~/signaldock-demo-secrets && cd ~/signaldock-demo-secrets
cat > .env <<EOF
SIGNALDOCK_COLLECTION_KEY=$(openssl rand -hex 32)
SIGNALDOCK_QUERY_KEY=$(openssl rand -hex 32)
SIGNALDOCK_DB_NAME=signaldock
SIGNALDOCK_DB_USER=signaldock
SIGNALDOCK_DB_PASSWORD=$(openssl rand -hex 24)
SIGNALDOCK_TIME_ZONE=Asia/Seoul
EOF
printf 'demo:%s\n' "$(openssl passwd -apr1)" > htpasswd   # asks for the Dashboard password twice
```

The collection key and the query key must differ (the server refuses to start otherwise); `openssl rand` makes that certain. Give the Dashboard user name and password only to people who ask.

Copy both files over IAP and install them with owner and mode:

```bash
Z="--zone asia-northeast3-a --tunnel-through-iap"
gcloud compute scp $Z .env htpasswd signaldock-demo:/tmp/
gcloud compute ssh $Z signaldock-demo --command '
  sudo install -m 600 -o root -g root /tmp/.env /opt/signaldock/.env &&
  sudo install -m 640 -o root -g www-data /tmp/htpasswd /etc/nginx/signaldock.htpasswd &&
  rm -f /tmp/.env /tmp/htpasswd'
```

Then delete the local copies, or keep them in a password manager:

```bash
cd ~ && rm -rf ~/signaldock-demo-secrets
```

## 6. TLS certificate

DNS must already answer with the VM address (step 3). nginx is stopped on purpose: it cannot start without the certificate. Port 80 is free, so certbot answers the Let's Encrypt challenge itself (`--standalone`). The two hooks are saved in the renewal config: on each renewal nginx stops for a few seconds, then starts and reads the new certificate. `certbot.timer` (installed with the package) runs the renewal twice a day.

On the VM:

```bash
sudo certbot certonly --standalone -d signaldock.stack-line.dev \
  -m <your email> --agree-tos --no-eff-email \
  --pre-hook 'systemctl stop nginx' --post-hook 'systemctl start nginx'
sudo nginx -t && systemctl is-active nginx          # active
sudo certbot renew --dry-run                        # the hooks run; nginx comes back
systemctl list-timers certbot.timer
```

If issuance fails: check `dig +short signaldock.stack-line.dev`, that port 80 is reachable (Cloudflare proxy must be off), and that nginx is stopped (`sudo systemctl stop nginx`); then run the command again. Let's Encrypt allows 5 failed attempts an hour per host name.

## 7. Verify (before the stack is running)

From your computer, check the VM has no service account and the VPC has only two rules:

```bash
gcloud compute instances describe signaldock-demo --zone asia-northeast3-a --format='value(serviceAccounts)'   # must print nothing (spec D6, section 7)
gcloud compute firewall-rules list --filter=network:signaldock-demo                                           # only the two rules
```

From your computer (replace `H` if you use another name):

```bash
H=https://signaldock.stack-line.dev
curl -sI $H/ | head -3                                   # 401 and WWW-Authenticate: Basic
curl -s -o /dev/null -w '%{http_code}\n' $H/v1/events    # 401 (no basic auth)
curl -s -o /dev/null -w '%{http_code}\n' $H/v1/events:batch   # 403 (GET is not allowed there)
curl -s -o /dev/null -w '%{http_code}\n' -X POST -d '{}' $H/v1/events:batch   # 502 now; the server answers once the stack runs
curl -s -o /dev/null -w '%{http_code}\n' $H/ready        # 502 now; 200 once the stack runs
curl -s -o /dev/null -w '%{http_code}\n' $H/deployed.txt # 404 until the first deploy writes it
curl -s -o /dev/null -w '%{http_code}\n' http://signaldock.stack-line.dev/ready   # 301
```

Over the limit (5 requests a second per IP, burst 10): expect a mix of 502 (or the server status) and 429, and `Retry-After: 1` with a JSON body.

```bash
for i in $(seq 40); do curl -s -o /dev/null -w '%{http_code}\n' -X POST -d '{}' $H/v1/events:batch & done | sort | uniq -c
wait
for i in $(seq 40); do curl -si -X POST -d '{}' $H/v1/events:batch; done | grep -i -m1 -A6 '^HTTP/.* 429'
```

Basic auth works (the Dashboard shows once the stack runs):

```bash
curl -s -o /dev/null -w '%{http_code}\n' -u demo $H/    # asks for the password; 502 now, 200 once the stack runs
```

On the VM:

```bash
sudo ss -ltn | grep -E ':(80|443|8080|5173|5432)\b'      # 80 and 443 on 0.0.0.0; 8080 and 5173 only on 127.0.0.1 (once the stack runs)
sudo tail -n 3 /var/log/nginx/signaldock.json            # JSON lines; no query string; no Authorization
```

## 8. Verify after the stack runs (ticket 05)

On the VM, with the stack up:

```bash
sudo docker ps --format '{{.Names}} {{.Ports}}' | grep '0.0.0.0' || echo "no 0.0.0.0 ports"
# The metadata server answers the host, not a container. The first command must print a response; the second must time out (exit 28).
curl -m 3 -H 'Metadata-Flavor: Google' http://169.254.169.254/
sudo docker run --rm --network signaldock_default curlimages/curl -m 3 -H 'Metadata-Flavor: Google' http://169.254.169.254/; echo "exit=$?"
```

Then reboot and check that the rule and nginx come back:

```bash
sudo reboot
# wait, SSH in again
sudo iptables -S DOCKER-USER                       # the DROP rule is there
systemctl is-active signaldock-docker-user nginx docker
```

Record the results in the PR description. A step you did not run is "not run".

## 9. Deploy and reset scripts on the VM

Files: `infra/vm/signaldock-deploy.{sh,service,timer}`, `signaldock-reset.{sh,service,timer}`, `signaldock-disk-check.{service,timer}`, and `install.sh`. The scripts run from `/usr/local/sbin`, never from the checked-out tag. The tag supplies only the Compose files and the tracking plan, because they must match its images. `install.sh` copies the scripts and units from `origin/main` and enables the three timers. It is idempotent.

A new VM runs `install.sh` from cloud-init. **A VM that already exists** (created from an earlier `main`): cloud-init does not run again, so you run it by hand, once, and again whenever a PR changes `infra/vm/`:

```bash
gcloud compute ssh signaldock-demo --zone asia-northeast3-a --tunnel-through-iap -- \
  "sudo bash -c 'git -C /opt/signaldock/src fetch origin main && git -C /opt/signaldock/src show origin/main:infra/vm/install.sh | bash'"
systemctl list-timers 'signaldock-*'     # deploy every 5 min, reset 04:00 Asia/Seoul, disk check hourly
```

Do not re-apply Terraform for this. `cloud-init.yaml` is the instance's `user-data`; changing it makes `terraform plan` show a metadata change on the instance. By the Google provider docs, `metadata` updates in place and cloud-init does not run again, so the change does nothing on the VM. Read the plan: "update in-place" is fine, "must be replaced" is not (cancel; the VM would lose `.env` and the certificate). Not run here (no cloud access).

## 10. Tag ruleset (owner only)

Only the admin may create, move, or delete `demo-v*` tags. Without this, anyone with write access can deploy to the VM.

GitHub, repository Settings, Rules, Rulesets, New ruleset, New tag ruleset:

- Name `demo-tags`. Enforcement status: Active.
- Bypass list: Repository admin, "Always". No other role or app.
- Target tags: Include by pattern `demo-v*`.
- Rules: Restrict creations, Restrict updates, Restrict deletions.

Check (read only): `gh api repos/HyeokjaeChoi/signaldock/rulesets` lists `demo-tags` with `"target": "tag"` and `"enforcement": "active"`.

The VM also refuses a tag that is not an ancestor of `origin/main`, so a bypass slip cannot deploy a side branch.

## 11. First tag and public packages (owner only)

Order matters. The VM needs the stack files on `main`, `.env`, htpasswd, and the certificate (sections 3 to 6) first.

1. Check on `main`, then tag and push. Pick a version that is new; an image tag is never overwritten.

   ```bash
   git fetch origin && git tag demo-v0.1.0 origin/main && git push origin demo-v0.1.0
   ```

2. The workflow `demo-deploy` builds and pushes `ghcr.io/hyeokjaechoi/signaldock-server:demo-v0.1.0` and `signaldock-dashboard:demo-v0.1.0`. Its second job waits for `/deployed.txt`, so it fails until step 3 is done. Re-run only that job afterwards.
3. Set both packages to **Public**. GitHub, your profile, Packages, `signaldock-server`, Package settings, Danger Zone, Change visibility, Public; then the same for `signaldock-dashboard`. **This cannot be undone.** It is safe because the images hold no secrets: `.dockerignore` excludes `.env` and `.env.*` (only `.env.example` is allowed in), and the Dashboard query key is read from the container environment at start, never at build (`dashboard/Dockerfile`). Until the packages are public, `docker compose pull` on the VM is denied. The deploy logs a warning and tries again every 5 minutes; nothing is marked failed.
4. If a later run fails with `permission_denied: write_package`: Package settings, Manage Actions access, add this repository with the Write role. The workflow sets the `org.opencontainers.image.source` label so the link is made on the first push; this is not verified.
5. Wait at most 5 minutes, then check:

   ```bash
   curl -s https://signaldock.stack-line.dev/deployed.txt        # demo-v0.1.0
   curl -s -o /dev/null -w '%{http_code}\n' https://signaldock.stack-line.dev/ready   # 200
   ```

   Then run section 8 on the VM, and a first `sudo systemctl start signaldock-reset` (section 13) so the Dashboard has data.

## 12. Deploy by hand, and what a deploy does

Normal path: push a tag (section 11). The timer runs `signaldock-deploy` every 5 minutes. To deploy now (for example after a fix, or to retry):

```bash
gcloud compute ssh signaldock-demo --zone asia-northeast3-a --tunnel-through-iap -- sudo systemctl start signaldock-deploy
gcloud compute ssh signaldock-demo --zone asia-northeast3-a --tunnel-through-iap -- sudo journalctl -u signaldock-deploy -n 60 --no-pager
```

What it does, in order: take `/run/signaldock.lock` (shared with the reset; if busy it skips and the timer comes back); `git fetch --tags --force`; take the newest `demo-vX.Y.Z` by version order; stop if it equals `/opt/signaldock/current-tag` or is a line in `/opt/signaldock/failed-tags`; refuse (and list as failed) a tag that is not an ancestor of `origin/main`; check out the tag; `docker compose pull`; `up -d --no-build`; wait up to 180 s for `http://127.0.0.1:8080/ready` and the Dashboard at `http://127.0.0.1:5173/`; write `current-tag` and `/var/www/signaldock/deployed.txt` (tag only); remove dangling images and signaldock images that are neither the new nor the previous tag. It only reads `.env` through `--env-file`. A tag whose images cannot be pulled yet is not a failure (the CI build may still run); it retries on the next run.

Only the newest tag is ever deployed. To go back to an older version, make a new tag on `main` that holds the older code (after a revert PR).

A change to `infra/vm/` does not reach the VM with a tag: run `install.sh` (section 9).

## 13. Reset (daily, by hand, and disk)

The timer `signaldock-reset.timer` runs at 04:00 Asia/Seoul (`Persistent=true`: a VM that was off at that time resets at boot). It takes the same lock as the deploy and waits up to 15 minutes for a running deploy. It runs `docker compose down --volumes` (this deletes the DB volume, the only volume), `up -d --no-build` with the current tag (Flyway creates the schema), waits for `/ready`, and runs `scripts/demo-seed.py` as user `nobody` with the collection key from `.env` in its environment only. The key is never printed. The site returns 502 for about a minute.

```bash
sudo systemctl start signaldock-reset          # by hand
sudo journalctl -u signaldock-reset -n 40 --no-pager
systemctl list-timers 'signaldock-*'
```

Disk backstop: `signaldock-disk-check.timer` runs every hour and calls `signaldock-reset.sh --if-disk-above 80`. At 80 % of the 30 GB root disk (24 GB) it resets early. 80 % leaves 6 GB. At the highest rate one IP can reach (5 requests a second of 256 KB, about 1.3 MB a second) that is about 80 minutes, so an hourly check is just enough; and only a caller with the collection key stores anything (others get 401). The daily reset is the main control. If the last reset was less than 6 hours ago, the check does not reset again; it logs an error and fails, because the database is then not the cause. Look at `sudo docker system df`, `sudo du -xh /var/log --max-depth=1`, and `/var/log/nginx`.

If the seed fails, the database is empty or partly seeded. Read the journal; `sudo systemctl start signaldock-reset` again starts from a clean volume.

## 14. A failed deploy

Signs: the workflow job `wait-deployed` fails after 15 minutes; `deployed.txt` shows the old tag; `systemctl status signaldock-deploy` shows `failed`.

```bash
sudo journalctl -u signaldock-deploy -n 100 --no-pager     # the reason, and the server log tail
cat /opt/signaldock/failed-tags /opt/signaldock/current-tag
sudo docker ps -a --format '{{.Names}} {{.Status}}'
```

What the script did: wrote the tag to `failed-tags`, then started the previous tag (`current-tag`) again. With no previous tag, the demo is down until a good tag deploys. If the log says the rollback failed too, the demo is down: read `docker compose ... logs`, fix by hand, or deploy a new good tag.

To retry the same tag (the cause was outside the code, for example a full disk): remove its line and start the deploy.

```bash
sudo sed -i '/^demo-v0.2.0$/d' /opt/signaldock/failed-tags
sudo systemctl start signaldock-deploy
```

To fix the code: merge the fix, and push a new tag with a higher version. A failed tag is never moved (the ruleset forbids it).

Rollback test (owner, optional): push a normal new tag. Before the timer fires, stop the timer and run the deploy by hand with `SIGNALDOCK_TEST_FAIL_TAG` set to the new tag. The readiness wait then fails only for that tag, so the rollback to the old tag uses the normal checks. The script logs when the override is active.

```bash
sudo systemctl stop signaldock-deploy.timer
sudo SIGNALDOCK_TEST_FAIL_TAG=demo-v0.2.0 /usr/local/sbin/signaldock-deploy.sh
cat /opt/signaldock/failed-tags        # the new tag is listed
curl -s https://signaldock.stack-line.dev/deployed.txt   # still the old tag
# Retry for real: remove the tag from failed-tags, start the timer again.
sudo sed -i '/^demo-v0.2.0$/d' /opt/signaldock/failed-tags
sudo systemctl start signaldock-deploy.timer
```

## 15. Rollback and Flyway (spec section 7)

The server runs `Flyway.configure().dataSource(dataSource).load().migrate()` with no other setting (`server/persistence/.../Database.kt`). Flyway 13.10.0 defaults apply: `validateOnMigrate` true and `ignoreMigrationPatterns` `*:future`.

Checked on 2026-10-25 with the `flyway/flyway:13.10.0-alpine` image (the CLI, same defaults as the API) against PostgreSQL 17.6: apply V1 to V3, then run `migrate` and `validate` with only V1 and V2 on disk. Result: both succeed (exit 0). Flyway prints `WARNING: Schema "public" has a version (3) that is newer than the latest available migration (2) !` and `No migration necessary`; `info` shows V3 as `Future`. So an older image **starts** on a database that a newer image migrated. Flyway does not block it, and Flyway cannot undo a migration (the OSS edition has no undo).

What this means for a rollback after a newer tag added a migration `V<n>`:

- Whether the old code works on the new schema depends on the migration. An added table or nullable column: the old code ignores it and works. A drop, rename, type change, or new `NOT NULL` column without default: the old code can fail at run time, and `/ready` may still be 200 until it touches the table. The automatic rollback checks only `/ready`.
- Operator action: **when the failed tag added a migration, reset instead of trusting the rollback.** The data is synthetic. After the rollback, run `sudo systemctl start signaldock-reset` (section 13): it deletes the volume, so the old image creates its own schema. The deploy log says this when it rolls back.
- To know whether a tag added a migration: `git diff demo-v<old> demo-v<new> --stat -- server/persistence/src/main/resources/db/migration`.

The Flyway check ran on the CLI image, not on the server image itself; the library default is the same. Not run: a rollback on the VM.

## 16. Key rotation (owner only)

One key of each kind (ADR-0044). Rotation is by hand and has downtime. The data is synthetic, so rotate by editing `.env` and resetting.

```bash
gcloud compute ssh signaldock-demo --zone asia-northeast3-a --tunnel-through-iap
openssl rand -hex 32                                  # a new value; copy it from the terminal
sudoedit /opt/signaldock/.env                         # replace SIGNALDOCK_COLLECTION_KEY and/or SIGNALDOCK_QUERY_KEY (and SIGNALDOCK_DB_PASSWORD with openssl rand -hex 24)
sudo systemctl start signaldock-reset                 # recreates the stack with the new values and seeds with the new collection key
```

The two keys must differ (the server refuses to start otherwise). A new DB password only works with a new volume: PostgreSQL reads it at the first start, and the reset makes a new volume. Check afterwards: `curl -s -o /dev/null -w '%{http_code}\n' https://signaldock.stack-line.dev/ready` is 200 and the Dashboard shows data.

Dashboard password (htpasswd): on your computer `printf 'demo:%s\n' "$(openssl passwd -apr1)" > htpasswd`, then copy it as in section 5 (`install -m 640 -o root -g www-data`), delete the local file, and run `sudo systemctl reload nginx`. No reset is needed.

If a key leaked: rotate at once, then read the access log (`/var/log/nginx/signaldock.json`, kept 7 days) for the IPs that used it.

## 17. Teardown on 2027-01-16

The free-trial credit ends 2027-01-16 (UTC). Do these in order.

1. Stop deploys: open a PR that deletes `.github/workflows/demo-deploy.yml` (or disables it), and merge it. Remove the README limitation that names the demo URL (ADR-0043 consequences), and keep the screenshots and the video.
2. Delete the Cloudflare record and the cloud resources:

   ```bash
   terraform -chdir=infra/terraform destroy -var cloudflare_zone_id="$ZONE_ID"
   ```

   The plan must show 7 resources to destroy (the DNS record is one). Check in the GCP console that no instance, disk, or reserved address is left, and that billing shows no charge.
3. GitHub: delete both GHCR packages (profile, Packages, each package, Package settings, Delete this package), delete the ruleset `demo-tags`, and delete the `demo-v*` tags (`git push origin --delete <tag>` after removing the ruleset).
4. Cloudflare: delete the API token (My Profile, API Tokens), and confirm the record `signaldock.stack-line.dev` is gone.
5. Delete the uptime check and its alert (ticket 07), and the budget alerts.
6. Local: delete `infra/terraform/terraform.tfstate*` and your copy of it, `~/signaldock-demo-secrets` if it still exists, and `CLOUDFLARE_API_TOKEN` from any shell profile.
7. Mark the cloud demo ADRs as ended in a docs PR if the owner wants (the ADRs say the demo ends on this date).
