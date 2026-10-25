#!/bin/bash
# Build the static GoAccess report from the host nginx JSON log (ADR-0044 item 5, ticket 07).
# Runs as www-data from signaldock-report.service. Input: the live log and the rotated ones (7 days).
# IPs are anonymized (last octet of IPv4, last 80 bits of IPv6 become zero). No real-time WebSocket.
# The log format below must match `log_format signaldock` in infra/nginx/signaldock.conf.
set -euo pipefail

LOG=${SIGNALDOCK_REPORT_LOG:-/var/log/nginx/signaldock.json}
OUT=${SIGNALDOCK_REPORT_OUT:-/var/www/signaldock-reports/index.html}

# Oldest first: .7.gz ... .2.gz, .1 (delaycompress leaves it plain), then the live file. zcat -f reads both kinds.
files=()
for n in 7 6 5 4 3 2; do [ -f "$LOG.$n.gz" ] && files+=("$LOG.$n.gz"); done
[ -f "$LOG.1" ] && files+=("$LOG.1")
[ -f "$LOG" ] && files+=("$LOG")
[ "${#files[@]}" -gt 0 ] || { echo "no log file yet; nothing to report"; exit 0; }

# Time field: %d date, literal T, %t time, then "+" and the zone offset is skipped. The VM clock is UTC (+00:00), so nginx writes +00:00; the runbook checks timedatectl.
tmp=$(mktemp --suffix=.html "${OUT%.html}.XXXXXX")  # goaccess wants the .html extension
trap 'rm -f "$tmp"' EXIT
zcat -f "${files[@]}" | goaccess - \
  --log-format='{"time":"%dT%t+%^","remote_addr":"%h","remote_user":"%e","method":"%m","uri":"%U","protocol":"%H","status":%s,"bytes":%b,"request_length":%^,"request_time":%T,"limit_req":"%^","host":"%v","referer":"%R","user_agent":"%u"}' \
  --date-format='%Y-%m-%d' --time-format='%T' \
  --anonymize-ip --no-progress --no-global-config \
  --html-report-title='SignalDock demo access log' \
  -o "$tmp"
chmod 0644 "$tmp"
mv -f "$tmp" "$OUT"   # atomic: nginx never serves a half-written file
trap - EXIT
