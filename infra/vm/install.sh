#!/bin/bash
# Install the deploy and reset scripts and timers on the VM (ticket 05). Idempotent; run as root.
# cloud-init calls it on first boot. On an existing VM the owner runs it over IAP SSH (docs/runbook-demo.md):
#
#   sudo bash -c 'git -C /opt/signaldock/src fetch origin main && git -C /opt/signaldock/src show origin/main:infra/vm/install.sh | bash'
#
# Files come from the VM clone's origin/main (git archive), not from the working tree, which sits on a deployed tag.
set -euo pipefail

SRC=/opt/signaldock/src
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
git -C "$SRC" archive origin/main infra/vm | tar -x -C "$tmp"
d=$tmp/infra/vm

install -d -m 0755 /opt/signaldock /var/www/signaldock
install -m 0755 "$d/signaldock-deploy.sh" "$d/signaldock-reset.sh" /usr/local/sbin/
units=(signaldock-deploy.service signaldock-deploy.timer signaldock-reset.service signaldock-reset.timer
       signaldock-disk-check.service signaldock-disk-check.timer)
for u in "${units[@]}"; do install -m 0644 "$d/$u" /etc/systemd/system/; done
systemctl daemon-reload
systemctl enable --now signaldock-deploy.timer signaldock-reset.timer signaldock-disk-check.timer
echo "installed; next runs:"
systemctl list-timers 'signaldock-*' --no-pager
