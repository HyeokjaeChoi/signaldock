# Runbook: cloud demo

Part 1: setup by the owner (spec section 4, steps 1 to 4). Later parts (tag ruleset, GHCR packages, uptime check, key rotation, incidents, teardown) are added by ticket 05 and later.

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
