#!/bin/bash
# Simulator verification of SDK durability (iOS ticket 15, ADR-0047 D15, ADR-0011, ADR-0029). Local only: not run in CI.
#
# Usage: scripts/simulator-durability.sh <simulator-udid> [case ...]
# Cases: kill_store kill_send restart update reinstall optout offline   (default: all)
#
# The Debug build of compatibility/consumer-ios runs one phase per launch (SIMCTL_CHILD_SIGNALDOCK_PHASE, code in
# Sources/Phases.swift, Debug only: ADR-0047 "Amendment (2026-10-29, ticket 15, durability phases)"). Between phases
# the script ends the process with kill -9, installs over the app (update), or uninstalls and installs. It reads the
# queue from the app container (sqlite3, read only) and the stored events from the server query API.
# Pass rule: no lost event (every event that track() reported as stored reaches the server) and no unreported event,
# and the queue is as the case expects. A resend shows only as `duplicate` in the ACK (ADR-0047 ticket 15 amendment).
#
# Needs: a running verification stack (docker compose, own project and ports), full Xcode with an iOS Simulator
# runtime, sqlite3, python3, curl. Environment variables:
#   SIGNALDOCK_COLLECTION_KEY  collection key of the server (required; never committed)
#   SIGNALDOCK_QUERY_KEY       query key of the server (required)
#   SIGNALDOCK_ENDPOINT        server URL the Simulator and this script use (default http://127.0.0.1:${SIGNALDOCK_SERVER_PORT:-8080})
#   SIMULATOR_DURABILITY_SKIP_BUILD=1  reuse the app that scripts/verify-consumer-ios.sh built earlier
#   IOS_WERROR=1               passed to the build
# Offline means the app endpoint is a closed local port (http://127.0.0.1:9): no network setting of the Mac changes.
# A Simulator that this script boots is shut down at exit. It never runs docker compose.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
udid="${1:?usage: scripts/simulator-durability.sh <simulator-udid> [case ...]}"
shift || true
cases=("$@")
[ ${#cases[@]} -gt 0 ] || cases=(kill_store kill_send restart update reinstall optout offline)
for c in "${cases[@]}"; do
  case "$c" in kill_store | kill_send | restart | update | reinstall | optout | offline) ;; *) echo "error: unknown case '$c'" >&2; exit 2 ;; esac
done

: "${SIGNALDOCK_COLLECTION_KEY:?set SIGNALDOCK_COLLECTION_KEY (collection key of the verification stack)}"
: "${SIGNALDOCK_QUERY_KEY:?set SIGNALDOCK_QUERY_KEY (query key of the verification stack)}"
ENDPOINT="${SIGNALDOCK_ENDPOINT:-http://127.0.0.1:${SIGNALDOCK_SERVER_PORT:-8080}}"
OFFLINE="http://127.0.0.1:9"
# Keys travel in the environment, not in argv (visible in ps).
export SIMCTL_CHILD_SIGNALDOCK_COLLECTION_KEY="$SIGNALDOCK_COLLECTION_KEY"
export SD_QUERY_KEY="$SIGNALDOCK_QUERY_KEY"

# Only a Simulator that simctl lists. A physical device is never in this list.
devname="$(xcrun simctl list devices -j | python3 -c '
import json, sys
for runtime, devices in json.load(sys.stdin)["devices"].items():
    for d in devices:
        if d["udid"] == sys.argv[1] and "SimRuntime.iOS-" in runtime:
            print(d["name"] + " / " + runtime.rsplit(".", 1)[-1])
' "$udid")"
[ -n "$devname" ] || { echo "refusing $udid: not an iOS Simulator (iOS runtime) listed by 'xcrun simctl list devices'" >&2; exit 1; }
curl -fsS -o /dev/null "$ENDPOINT/ready" || { echo "error: server not ready at $ENDPOINT (start the verification stack first)" >&2; exit 1; }

BUNDLE=""
work="$(mktemp -d)"
booted_here=0
cleanup() {
  rm -rf "$work"
  xcrun simctl terminate "$udid" "$BUNDLE" >/dev/null 2>&1 || true
  if [ "$booted_here" = 1 ]; then xcrun simctl shutdown "$udid" >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT

if ! xcrun simctl list devices | grep "$udid" | grep -q Booted; then
  xcrun simctl boot "$udid"
  booted_here=1
fi
xcrun simctl bootstatus "$udid" -b >/dev/null

if [ "${SIMULATOR_DURABILITY_SKIP_BUILD:-0}" != 1 ]; then
  scripts/verify-consumer-ios.sh
fi
PRODUCTS="$ROOT/compatibility/consumer-ios/build/DerivedData/Build/Products"
APP="$PRODUCTS/Debug-iphonesimulator/ConsumerIOS.app"
[ -d "$APP" ] || { echo "error: no Debug app at $APP (run without SIMULATOR_DURABILITY_SKIP_BUILD)" >&2; exit 1; }
BUNDLE="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$APP/Info.plist")"

# Release must not contain the phase code (ADR-0047 amendment).
# strings output goes to a file first: "strings | grep -q" under pipefail reads a SIGPIPE (141) as "not found".
# With SIMULATOR_DURABILITY_SKIP_BUILD=1 this checks the Release product of the earlier build (it may be stale).
REL="$PRODUCTS/Release-iphonesimulator/ConsumerIOS.app/ConsumerIOS"
[ -f "$REL" ] || { echo "error: no Release product at $REL (run without SIMULATOR_DURABILITY_SKIP_BUILD)" >&2; exit 1; }
strings -a "$REL" >"$work/release-strings.txt"
if [ "$(grep -c SIGNALDOCK_PHASE "$work/release-strings.txt" || true)" != 0 ]; then
  echo "error: the Release binary contains the phase marker SIGNALDOCK_PHASE" >&2
  exit 1
fi
echo "release check: no SIGNALDOCK_PHASE in $REL ($(wc -l <"$work/release-strings.txt" | tr -d ' ') strings read; build skipped: ${SIMULATOR_DURABILITY_SKIP_BUILD:-0})"

echo "simulator: $devname ($udid)   $(xcodebuild -version | tr '\n' ' ')"
echo "commit: $(git rev-parse --short HEAD)   server: $ENDPOINT   app: $BUNDLE (Debug)"

FROM="$(date -u -v-1H +%Y-%m-%dT%H:%M:%SZ)"
TO="$(date -u -v+1d +%Y-%m-%dT%H:%M:%SZ)"

fail() { echo "FAILED: $*" >&2; exit 1; }

# --- app and container helpers ---
fresh_install() {
  xcrun simctl terminate "$udid" "$BUNDLE" >/dev/null 2>&1 || true
  xcrun simctl uninstall "$udid" "$BUNDLE" >/dev/null 2>&1 || true
  xcrun simctl install "$udid" "$APP"
}
data() { xcrun simctl get_app_container "$udid" "$BUNDLE" data; }
sdk_dir() { echo "$(data)/Library/Application Support/signaldock"; }
result_file() { echo "$(data)/Documents/phase-result.txt"; }
res() { sed -n "s/^$1=//p" "$result" | head -1; }

# launch <phase> <endpoint> [count] [name]: starts one phase and sets $pid. Without wait=started it waits for the
# result file (status pass) and fails on a status other than pass; a phase "burst" waits for status started instead.
launch() {
  local phase="$1" endpoint="$2" count="${3:-5}" name="${4:-dur_event}" want="${wait_for:-pass}" out
  rm -f "$(result_file)" 2>/dev/null || true
  out="$(env SIMCTL_CHILD_SIGNALDOCK_AUTORUN=0 SIMCTL_CHILD_SIGNALDOCK_PHASE="$phase" SIMCTL_CHILD_SIGNALDOCK_ENDPOINT="$endpoint" \
    SIMCTL_CHILD_SIGNALDOCK_PHASE_COUNT="$count" \
    SIMCTL_CHILD_SIGNALDOCK_PHASE_NAME="$name" SIMCTL_CHILD_SIGNALDOCK_PHASE_TIMEOUT="${PHASE_TIMEOUT:-240}" \
    xcrun simctl launch --terminate-running-process "$udid" "$BUNDLE")"
  pid="${out##*: }"
  result="$(result_file)"
  local deadline=$((SECONDS + ${PHASE_TIMEOUT:-240} + 30))
  until [ -f "$result" ] && [ -n "$(res status)" ]; do
    [ $SECONDS -lt $deadline ] || fail "phase $phase: no result within the deadline"
    sleep 0.3
  done
  [ "$(res status)" = "$want" ] || { cat "$result" >&2; fail "phase $phase: status $(res status), expected $want"; }
  echo "  phase $phase (pid $pid): $(grep -Ev '^(eventIds|phase|pid|status)=' "$result" | tr '\n' ' ')"
}
kill9() { kill -9 "$1"; sleep 1; kill -0 "$1" 2>/dev/null && fail "process $1 survived kill -9"; return 0; }

queue_db() { echo "$(sdk_dir)/queue.db"; }
queue_ids() { sqlite3 -readonly "$(queue_db)" 'select eventId from events order by seq'; }
installation_id() { tr -d '[:space:]' <"$(sdk_dir)/installation-id"; }

# Event IDs of one installation at the server, one per line, in order of receipt (a repeated ID shows up twice).
server_ids() {
  python3 - "$ENDPOINT" "$1" "$FROM" "$TO" <<'EOF'
import json, os, sys, urllib.parse, urllib.request
base, inst, frm, to = sys.argv[1:5]
key = os.environ["SD_QUERY_KEY"]
cursor = None
while True:
    q = {"installationId": inst, "from": frm, "to": to, "sort": "receivedAt_asc", "limit": "100"}
    if cursor:
        q["cursor"] = cursor
    req = urllib.request.Request(base + "/v1/events?" + urllib.parse.urlencode(q), headers={"Authorization": "Bearer " + key, "SignalDock-Contract-Version": "1"})
    page = json.load(urllib.request.urlopen(req, timeout=30))
    for item in page["items"]:
        print(item["eventId"])
    cursor = page.get("nextCursor")
    if not cursor or not page["items"]:
        break
EOF
}

# server_matches <installationId> <expected-file> [allowed extra events]: no lost event and no event the app did not
# report. A repeated event ID cannot be stored (the server ignores a conflicting insert), so the resends that a kill
# causes show up only as "duplicate" in the drain ACK, which launch prints as lastAck.
server_matches() {
  local inst="$1" expected="$2" extra="${3:-0}"
  server_ids "$inst" >"$work/server.txt"
  local lost unknown
  lost="$(comm -23 <(sort -u "$expected") <(sort -u "$work/server.txt") | wc -l | tr -d ' ')"
  unknown="$(comm -13 <(sort -u "$expected") <(sort -u "$work/server.txt") | wc -l | tr -d ' ')"
  echo "  server: expected $(sort -u "$expected" | wc -l | tr -d ' '), stored $(wc -l <"$work/server.txt" | tr -d ' '), lost $lost, unknown $unknown (allowed $extra)"
  [ "$lost" -eq 0 ] || fail "$lost stored event(s) never reached the server"
  [ "$unknown" -le "$extra" ] || fail "$unknown event(s) at the server that the app did not report"
}

# seed <name>: fresh install, store 5 events offline, then the process is killed. Sets $inst and $seeded (file).
seed() {
  fresh_install
  launch store "$OFFLINE" 5 "$1"
  inst="$(res installationId)"
  [ -n "$inst" ] || fail "seed reported no installation ID"
  tr ',' '\n' <<<"$(res eventIds)" >"$work/seeded.txt"
  [ "$(wc -l <"$work/seeded.txt" | tr -d ' ')" -eq 5 ] || fail "seed reported no 5 event IDs"
  [ "$(res queuedEvents)" = 5 ] || fail "seed: queuedEvents $(res queuedEvents), expected 5"
  kill9 "$pid"
}

# kept: the second launch sees the same installation ID and the same queue (ids in order), then delivers.
check_kept() {
  launch snapshot "$OFFLINE"
  [ "$(res installationId)" = "$inst" ] || fail "installation ID changed: $(res installationId) (was $inst)"
  [ "$(res queuedEvents)" = 5 ] || fail "queuedEvents $(res queuedEvents), expected 5"
  diff <(queue_ids) "$work/seeded.txt" >/dev/null || fail "queue holds other events than the seeded ones"
  echo "  queue: the 5 seeded events, installation ID kept"
  deliver "$work/seeded.txt"
}
# deliver <expected-file> [extra]: start the app against the server, wait for an empty queue, check the server.
deliver() {
  launch drain "$ENDPOINT"
  [ "$(res queuedEvents)" = 0 ] || fail "queue not empty after delivery"
  server_matches "$(installation_id)" "$1" "${2:-0}"
}

case_kill_store() {
  echo "== kill_store: kill -9 during a burst of track() calls (offline, nothing is sent)"
  fresh_install
  wait_for=started launch burst "$OFFLINE" 0 dur_kill_store
  local bpid="$pid" acked s1 s2
  acked="$(data)/Documents/acked.log"
  sleep 5
  s1="$(wc -c <"$acked" | tr -d ' ')"; sleep 1; s2="$(wc -c <"$acked" | tr -d ' ')"
  [ "$s2" -gt "$s1" ] || fail "burst was not collecting at kill time (acked.log $s1 -> $s2 bytes)"
  echo "  collecting before kill: pid $bpid, acked.log $s1 -> $s2 bytes"
  kill9 "$bpid"
  verify_burst
}

case_kill_send() {
  echo "== kill_send: kill -9 while delivery of a burst is in progress (real server)"
  fresh_install
  wait_for=started launch burst "$ENDPOINT" 0 dur_kill_send
  local bpid="$pid" inst_id n=0 stored deadline=$((SECONDS + 60))
  until [ -f "$(sdk_dir)/installation-id" ]; do
    [ $SECONDS -lt "$deadline" ] || fail "no installation ID within 60 s"
    sleep 0.2
  done
  inst_id="$(installation_id)"
  # Delivery has started when the server holds the first events; the burst keeps storing meanwhile.
  until [ "$n" -gt 0 ]; do
    [ $SECONDS -lt "$deadline" ] || fail "no event reached the server within 60 s"
    sleep 0.2
    n="$(server_ids "$inst_id" | wc -l | tr -d ' ')"
  done
  # Kill at the first sample where delivery is partial: some events delivered, others still queued.
  while :; do
    n="$(server_ids "$inst_id" | wc -l | tr -d ' ')"
    stored="$(grep -cE '^[0-9a-f-]{36}$' "$(data)/Documents/acked.log" || true)"
    [ "$n" -lt "$stored" ] && break
    [ $SECONDS -lt "$deadline" ] || fail "delivery was not partial within 60 s (server $n, stored $stored)"
    sleep 0.1
  done
  kill9 "$bpid"
  echo "  at kill: pid $bpid, $n events at the server, $stored stored by the app"
  verify_burst deliver
}

# After a killed burst: every reported success is in the queue or at the server; then deliver the rest.
verify_burst() {
  local acked_file="$work/acked.txt"
  # The last line may be cut by the kill: keep complete event IDs only.
  grep -E '^[0-9a-f-]{36}$' "$(data)/Documents/acked.log" >"$acked_file" || true
  local acked stored
  acked="$(wc -l <"$acked_file" | tr -d ' ')"
  [ "$acked" -gt 0 ] || fail "no stored event was reported"
  if [ "$(sqlite3 -readonly "$(queue_db)" 'pragma integrity_check')" != ok ]; then fail "queue.db integrity_check is not ok"; fi
  queue_ids >"$work/queue.txt"
  server_ids "$(installation_id)" >"$work/server-early.txt"
  cat "$work/queue.txt" "$work/server-early.txt" | sort -u >"$work/known.txt"
  stored="$(wc -l <"$work/known.txt" | tr -d ' ')"
  [ "$(comm -23 <(sort -u "$acked_file") "$work/known.txt" | wc -l | tr -d ' ')" -eq 0 ] ||
    fail "a stored event is neither in the queue nor at the server"
  echo "  app reported $acked stored events; queue + server hold $stored (at most one in flight without a log line)"
  [ $((stored - acked)) -le 1 ] || fail "more than one event beyond the reported ones"
  deliver "$acked_file" 1
}

case_restart() {
  echo "== restart: process killed, same installation"
  seed dur_restart
  check_kept
}

case_update() {
  echo "== update: simctl install over the installed app"
  seed dur_update
  xcrun simctl install "$udid" "$APP"
  check_kept
}

case_reinstall() {
  echo "== reinstall: uninstall, install (new installation ID, empty queue, the unsent events are gone)"
  seed dur_reinstall
  xcrun simctl uninstall "$udid" "$BUNDLE"
  xcrun simctl install "$udid" "$APP"
  launch snapshot "$OFFLINE"
  [ -n "$(res installationId)" ] && [ "$(res installationId)" != "$inst" ] || fail "installation ID not new: $(res installationId)"
  [ "$(res queuedEvents)" = 0 ] || fail "queue not empty after reinstall"
  [ -z "$(queue_ids)" ] || fail "queue.db holds events after reinstall"
  [ "$(server_ids "$inst" | wc -l | tr -d ' ')" -eq 0 ] || fail "the old installation's events reached the server"
  echo "  new installation ID, empty queue, nothing from the old installation at the server"
}

case_optout() {
  echo "== optout: setOptOut(true), kill, opt-out kept and nothing sent, kill, setOptOut(false), collection resumes"
  fresh_install
  launch optout_set "$ENDPOINT" 5 dur_optout; local p1="$pid"; inst="$(res installationId)"
  kill9 "$p1"
  [ -f "$(sdk_dir)/opt-out" ] || fail "marker file opt-out missing after the kill"
  launch optout_check "$ENDPOINT" 5 dur_optout; local p2="$pid"
  [ "$(res isOptedOut)" = true ] || fail "opt-out lost across the kill"
  [ "$(res queuedEvents)" = 0 ] || fail "events queued while opted out"
  [ "$(server_ids "$inst" | wc -l | tr -d ' ')" -eq 0 ] || fail "the server received events while opted out"
  kill9 "$p2"
  launch optout_off "$ENDPOINT" 5 dur_optout; local p3="$pid"
  [ "$(res isOptedOutAtStart)" = true ] || fail "opt-out lost at the third start"
  [ ! -f "$(sdk_dir)/opt-out" ] || fail "marker file still there after setOptOut(false)"
  { [ "$p1" != "$p2" ] && [ "$p1" != "$p3" ] && [ "$p2" != "$p3" ]; } || fail "phases did not run in distinct processes: $p1 $p2 $p3"
  echo "  processes $p1, $p2, $p3"
  kill9 "$p3"
  queue_ids >"$work/resumed.txt"
  # The third phase launched against the server, so its event may already be delivered; the queue file is then empty.
  deliver_resumed "$p3"
}
# The event that optout_off stored is in the queue or at the server; it must reach the server once.
deliver_resumed() {
  server_ids "$inst" >"$work/early.txt"
  cat "$work/resumed.txt" "$work/early.txt" | sort -u >"$work/expected.txt"
  [ "$(wc -l <"$work/expected.txt" | tr -d ' ')" -eq 1 ] || fail "expected exactly 1 event after opt-out was lifted"
  deliver "$work/expected.txt"
}

case_offline() {
  echo "== offline: 30 events stored with the endpoint a closed port, kill, restart online, delivery"
  fresh_install
  launch store "$OFFLINE" 30 dur_offline
  inst="$(res installationId)"
  tr ',' '\n' <<<"$(res eventIds)" >"$work/seeded.txt"
  [ "$(res queuedEvents)" = 30 ] || fail "queuedEvents $(res queuedEvents), expected 30"
  kill9 "$pid"
  diff <(queue_ids) "$work/seeded.txt" >/dev/null || fail "queue after the kill differs from the stored events"
  [ "$(server_ids "$inst" | wc -l | tr -d ' ')" -eq 0 ] || fail "events reached the server while offline"
  local failures
  failures="$(sqlite3 -readonly "$(queue_db)" 'select failureCount from retry_state')"
  [ "${failures:-0}" -ge 1 ] || fail "the failed offline send left no persisted retry state (failureCount '${failures:-}')"
  echo "  persisted retry state after the kill: failureCount $failures, nextAttemptAtMillis $(sqlite3 -readonly "$(queue_db)" 'select nextAttemptAtMillis from retry_state')"
  deliver "$work/seeded.txt"
}

for c in "${cases[@]}"; do "case_$c"; echo "  ok: $c"; done

echo "backup restore: not run (the Simulator cannot reproduce it; spec section 5)"
echo "full disk: not run (the Simulator cannot reproduce it; spec section 5)"
echo "all requested cases passed on $devname ($udid)"
