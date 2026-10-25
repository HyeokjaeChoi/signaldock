#!/bin/bash
# Pull-based tag deploy (ADR-0043 item 4, cloud-demo spec D9). Run by signaldock-deploy.timer every 5 minutes.
# Installed to /usr/local/sbin by infra/vm/install.sh. It never runs from the checked-out tag: the tag's
# files supply only the Compose files and the tracking plan (they must match the images).
#
# Order: lock, fetch tags, newest demo-vX.Y.Z, skip if current or failed, ancestor check against origin/main
# (git binary on the VM), check out the tag, pull, up -d, wait for /ready and the Dashboard, record. On failure: mark the tag
# failed and bring the previous tag back. The .env file is only passed to Compose with --env-file.
set -euo pipefail

# The overrides exist for tests; systemd sets none of them.
BASE=${SIGNALDOCK_BASE:-/opt/signaldock}
LOCK=${SIGNALDOCK_LOCK:-/run/signaldock.lock}
WEBROOT=${SIGNALDOCK_WEBROOT:-/var/www/signaldock}
READY_URL=${SIGNALDOCK_READY_URL:-http://127.0.0.1:8080/ready}
DASHBOARD_URL=${SIGNALDOCK_DASHBOARD_URL:-http://127.0.0.1:5173/}
READY_WAIT=${SIGNALDOCK_READY_WAIT:-180}
TEST_FAIL_TAG=${SIGNALDOCK_TEST_FAIL_TAG:-}  # test only: the readiness wait fails for this one tag
SRC=$BASE/src
ENVF=$BASE/.env
TAG_RE='^demo-v[0-9]+\.[0-9]+\.[0-9]+$'

log() { echo "signaldock-deploy: $*"; }

# Shared with the reset. Skip this run if the reset (or another deploy) holds it; the timer comes back in 5 minutes.
exec 9>"$LOCK"
flock -n 9 || { log "another deploy or reset is running; skipping"; exit 0; }

[ -r "$ENVF" ] || { log "ERROR: $ENVF is missing (runbook part 1, step 5)"; exit 1; }
touch "$BASE/failed-tags"
cd "$SRC"

git fetch --tags --force --prune origin || { log "ERROR: git fetch failed"; exit 1; }

newest=$(git tag --list 'demo-v*' --sort=-version:refname | grep -E "$TAG_RE" | head -n 1 || true)
current=$(cat "$BASE/current-tag" 2>/dev/null || true)
[ -n "$newest" ] || { log "no demo-v tag yet"; exit 0; }
[ "$newest" != "$current" ] || { log "$newest is already deployed"; exit 0; }
if grep -qxF "$newest" "$BASE/failed-tags"; then
  log "$newest is listed in failed-tags; skipping (a newer tag, or removing the line, retries)"; exit 0
fi

# The ancestor check runs with the VM's git against the VM's origin/main, before any file of the tag is used.
if ! git merge-base --is-ancestor "refs/tags/$newest^{commit}" origin/main; then
  log "ERROR: $newest is not on origin/main; refused"
  echo "$newest" >> "$BASE/failed-tags"
  exit 1
fi

checkout() { git -c advice.detachedHead=false checkout --force --detach "refs/tags/$1" >/dev/null; }
compose() {  # compose <tag> <args...>
  DEMO_TAG=$1 docker compose -f docker-compose.yml -f infra/compose.demo.yml --env-file "$ENVF" "${@:2}"
}
wait_ready() {  # wait_ready <tag>
  if [ -n "$TEST_FAIL_TAG" ] && [ "$1" = "$TEST_FAIL_TAG" ]; then
    log "TEST OVERRIDE active: SIGNALDOCK_TEST_FAIL_TAG=$TEST_FAIL_TAG; treating $1 as not ready"
    return 1
  fi
  local deadline=$((SECONDS + READY_WAIT))
  until curl -fsS -m 3 -o /dev/null "$READY_URL" 2>/dev/null \
    && curl -fsS -m 3 -o /dev/null "$DASHBOARD_URL" 2>/dev/null; do
    [ "$SECONDS" -lt "$deadline" ] || return 1
    sleep 3
  done
}
start() {  # start <tag>: files from the tag, stack up, ready
  checkout "$1" && compose "$1" up -d --no-build --remove-orphans && wait_ready "$1"
}

log "deploying $newest (current: ${current:-none})"
checkout "$newest"
if ! compose "$newest" pull --quiet; then
  # The tag can be fetched before CI has pushed the images, or the packages are not Public yet.
  # Nothing changed on the host, so this is not a failed deploy: the next run tries again.
  [ -z "$current" ] || checkout "$current"
  log "WARNING: images for $newest are not available yet (still building, or packages not Public); will retry"
  exit 0
fi

if start "$newest"; then
  echo "$newest" > "$BASE/current-tag"
  # Atomic write inside the web root: nginx never serves a half-written file.
  tmp=$(mktemp "$WEBROOT/.deployed.XXXXXX")
  echo "$newest" > "$tmp"
  chmod 0644 "$tmp"
  mv -f "$tmp" "$WEBROOT/deployed.txt"
  # Dangling layers, and tagged signaldock images other than the current and previous tag (rollback needs the previous).
  docker image prune -f >/dev/null
  docker image ls --format '{{.Repository}}:{{.Tag}}' \
    | grep -E '^ghcr.io/hyeokjaechoi/signaldock-(server|dashboard):' \
    | grep -vE ":($newest|${current:-none})$" | xargs -r docker rmi >/dev/null 2>&1 || true
  log "OK: $newest deployed"
  exit 0
fi

log "ERROR: $newest failed (server /ready or the Dashboard not ready within ${READY_WAIT}s, or up failed); marking it failed"
echo "$newest" >> "$BASE/failed-tags"
compose "$newest" logs --no-color --tail 40 server 2>&1 | sed 's/^/signaldock-deploy: server log: /' || true
if [ -n "$current" ]; then
  log "rolling back to $current"
  if start "$current"; then
    log "rolled back to $current. If $newest added a Flyway migration, the old image runs on the newer schema; run signaldock-reset (runbook)"
  else
    log "ERROR: rollback to $current failed too; the demo is down. Fix by hand (runbook, failed deploy)"
  fi
else
  log "ERROR: no previous tag to roll back to; the demo is down"
fi
exit 1
