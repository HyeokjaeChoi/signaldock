#!/bin/bash
# Daily demo reset (ADR-0044 item 3, cloud-demo spec D3). Run by signaldock-reset.timer at 04:00 Asia/Seoul,
# and by signaldock-disk-check.timer (hourly) with --if-disk-above PERCENT.
#
# Drops the stack with its volumes (the DB volume is the only one), starts the current tag again (Flyway
# creates the schema), and seeds. The collection key is read from .env by a grep and handed to the seed
# script in its environment only: it is never printed, and this script never runs with `set -x`.
set -euo pipefail

BASE=${SIGNALDOCK_BASE:-/opt/signaldock}
LOCK=${SIGNALDOCK_LOCK:-/run/signaldock.lock}
READY_URL=${SIGNALDOCK_READY_URL:-http://127.0.0.1:8080/ready}
DASHBOARD_URL=${SIGNALDOCK_DASHBOARD_URL:-http://127.0.0.1:5173/}
READY_WAIT=${SIGNALDOCK_READY_WAIT:-180}
LOCK_WAIT=${SIGNALDOCK_LOCK_WAIT:-900}
DISK_PATH=${SIGNALDOCK_DISK_PATH:-/}
MIN_GAP=${SIGNALDOCK_MIN_GAP:-21600}  # seconds; a disk-triggered reset is skipped this soon after a reset
SRC=$BASE/src
ENVF=$BASE/.env

log() { echo "signaldock-reset: $*"; }
disk_used() { df --output=pcent "$DISK_PATH" | tail -n 1 | tr -dc '0-9'; }
since_reset() { echo $(($(date +%s) - $(cat "$BASE/last-reset" 2>/dev/null || echo 0))); }
limit=

# --if-disk-above gate: disk below the limit is a no-op; disk above it right after a reset is an error.
# $1 (optional) is logged on the no-op path.
disk_gate() {
  used=$(disk_used)
  [ "$used" -ge "$limit" ] || { [ -z "${1:-}" ] || log "$1"; exit 0; }
  if [ "$(since_reset)" -lt "$MIN_GAP" ]; then
    log "ERROR: $DISK_PATH is ${used}% full but the last reset was less than $((MIN_GAP / 3600)) h ago; the DB is not the cause. Check docker system df and /var/log (runbook)"
    exit 1
  fi
}

if [ "${1:-}" = "--if-disk-above" ]; then
  limit=${2:?usage: signaldock-reset.sh [--if-disk-above PERCENT]}
  disk_gate
  log "$DISK_PATH is ${used}% full (limit ${limit}%); resetting early"
elif [ $# -gt 0 ]; then
  echo "usage: signaldock-reset.sh [--if-disk-above PERCENT]" >&2; exit 2
fi

# Same lock as the deploy. Wait for a running deploy instead of skipping: the reset is the daily job.
exec 9>"$LOCK"
flock -w "$LOCK_WAIT" 9 || { log "ERROR: lock busy for ${LOCK_WAIT}s; reset not run"; exit 1; }

# A reset that held the lock first (the 04:00 daily reset) can make this early reset useless: check again.
[ -z "$limit" ] || disk_gate "a reset ran while waiting for the lock; early reset not needed"

[ -r "$ENVF" ] || { log "ERROR: $ENVF is missing"; exit 1; }
tag=$(cat "$BASE/current-tag" 2>/dev/null || true)
[ -n "$tag" ] || { log "no deployed tag yet; nothing to reset"; exit 0; }

cd "$SRC"
git -c advice.detachedHead=false checkout --force --detach "refs/tags/$tag" >/dev/null
compose() { DEMO_TAG=$tag docker compose -f docker-compose.yml -f infra/compose.demo.yml --env-file "$ENVF" "$@"; }

log "resetting $tag"
compose down --volumes --remove-orphans
compose up -d --no-build

deadline=$((SECONDS + READY_WAIT))
until curl -fsS -m 3 -o /dev/null "$READY_URL" 2>/dev/null \
  && curl -fsS -m 3 -o /dev/null "$DASHBOARD_URL" 2>/dev/null; do
  [ "$SECONDS" -lt "$deadline" ] || { log "ERROR: /ready or the Dashboard not 200 within ${READY_WAIT}s after the reset"; exit 1; }
  sleep 3
done

key=$(grep -m1 '^SIGNALDOCK_COLLECTION_KEY=' "$ENVF" | cut -d= -f2-)
[ -n "$key" ] || { log "ERROR: SIGNALDOCK_COLLECTION_KEY is not set in $ENVF"; exit 1; }
# Seed as an unprivileged user: the script comes from the tag. python -I ignores PYTHON* variables and the cwd.
SIGNALDOCK_COLLECTION_KEY=$key setpriv --reuid=nobody --regid=nogroup --clear-groups \
  python3 -I "$SRC/scripts/demo-seed.py" --base-url "${READY_URL%/ready}" \
  || { log "ERROR: seed failed (exit $?); the database is empty or partly seeded"; exit 1; }

date +%s > "$BASE/last-reset"
log "OK: reset and seeded ($tag)"
