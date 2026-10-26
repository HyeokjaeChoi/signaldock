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

# goaccess and fail2ban come from cloud-init on a new VM; an older VM gets them here (ticket 07).
if ! command -v goaccess >/dev/null || ! command -v fail2ban-client >/dev/null; then
  DEBIAN_FRONTEND=noninteractive apt-get install -y goaccess fail2ban
fi

install -d -m 0755 /opt/signaldock /var/www/signaldock
install -d -m 0755 -o www-data -g www-data /var/www/signaldock-reports
install -m 0755 "$d/signaldock-deploy.sh" "$d/signaldock-reset.sh" "$d/signaldock-report.sh" /usr/local/sbin/
units=(signaldock-deploy.service signaldock-deploy.timer signaldock-reset.service signaldock-reset.timer
       signaldock-disk-check.service signaldock-disk-check.timer signaldock-report.service signaldock-report.timer)
for u in "${units[@]}"; do install -m 0644 "$d/$u" /etc/systemd/system/; done
systemctl daemon-reload

# Docker daemon hardening (ADR-0045 amendment, issue 14). This repo owns /etc/docker/daemon.json: the file is replaced, not merged.
# A change needs a daemon restart. The restart is automatic when no container runs (a new VM: this block is before the deploy
# timer) or when live-restore is already active (containers keep running). Only the first rollout on a running demo is left to
# the owner, because that restart stops the containers (ADR-0045 amendment 2026-10-26).
# The check and the restart run under the deploy/reset lock (same file as signaldock-deploy.sh): "no container runs" is also true
# between the reset's compose down and up. If the lock stays busy for 10 minutes, do not restart; print the NOTICE.
# The restart also re-applies the DOCKER-USER rule (PartOf in signaldock-docker-user.service).
notice="NOTICE: the running Docker daemon may lack the settings in /etc/docker/daemon.json. Run 'sudo systemctl restart docker' when a short stop is fine (ADR-0045 amendment 2026-10-26)."
install -d -m 0755 /etc/docker
changed=0
if ! cmp -s "$d/docker-daemon.json" /etc/docker/daemon.json; then
  install -m 0644 "$d/docker-daemon.json" /etc/docker/daemon.json
  changed=1
fi
exec 9>"${SIGNALDOCK_LOCK:-/run/signaldock.lock}"
if flock -w 600 9; then
  running=$(docker ps -q)
  live=$(docker info --format '{{.LiveRestoreEnabled}}')
  if [ "$changed" = 1 ] && { [ -z "$running" ] || [ "$live" = true ]; }; then systemctl restart docker; elif [ -n "$running" ] && [ "$live" != true ]; then echo "$notice"; fi
  flock -u 9
else
  echo "$notice (the deploy/reset lock was busy)"
fi

systemctl enable --now signaldock-deploy.timer signaldock-reset.timer signaldock-disk-check.timer signaldock-report.timer

# fail2ban jail on the nginx JSON log. The jail does not start if the log file is missing, and nginx has not
# run yet on a new VM, so make the file first (same owner and mode as logrotate-signaldock; nginx appends to it).
[ -e /var/log/nginx/signaldock.json ] || install -m 0640 -o www-data -g adm /dev/null /var/log/nginx/signaldock.json
install -m 0644 "$d/fail2ban/filter.d/signaldock-nginx-auth.conf" /etc/fail2ban/filter.d/
install -m 0644 "$d/fail2ban/jail.d/signaldock-nginx-auth.conf" /etc/fail2ban/jail.d/
systemctl enable fail2ban
systemctl restart fail2ban
echo "installed; next runs:"
systemctl list-timers 'signaldock-*' --no-pager
