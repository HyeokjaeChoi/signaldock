#!/bin/bash
# Harness for signaldock-reset.sh --if-disk-above (ticket 10). Runs in a container with fake docker/git/curl/df/setpriv:
#   docker run --rm -v "$PWD":/repo:ro ubuntu:24.04 bash /repo/infra/vm/test-reset-guard.sh
# shellcheck disable=SC2016  # the fakes expand their variables when they run, not here
set -euo pipefail

T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
mkdir -p "$T/bin" "$T/base/src/scripts"
for c in git curl docker python3; do printf '#!/bin/bash\necho "%s $*" >> "$T_LOG"\n' "$c" > "$T/bin/$c"; done
printf '#!/bin/bash\necho "Use%%"; echo " $(cat "$T/disk")%%"\n' > "$T/bin/df"
printf '#!/bin/bash\nwhile [[ $1 == --* ]]; do shift; done\nexec "$@"\n' > "$T/bin/setpriv"
: > "$T/base/src/scripts/demo-seed.py"
echo demo-v1.0.0 > "$T/base/current-tag"; echo SIGNALDOCK_COLLECTION_KEY=k > "$T/base/.env"
chmod +x "$T"/bin/*
export T PATH="$T/bin:$PATH" T_LOG="$T/calls" SIGNALDOCK_BASE="$T/base" SIGNALDOCK_LOCK="$T/lock" SIGNALDOCK_READY_WAIT=5
R=$(dirname "$0")/signaldock-reset.sh

# The disk-check run waits on the lock. A holder (the daily reset) writes last-reset and sets the disk
# percent ($1) before it lets go. The run starts only after the holder has the lock (marker file).
# Expects exit $2 and no docker calls.
waiting_run() {
  echo 90 > "$T/disk"; echo 0 > "$T/base/last-reset"; : > "$T_LOG"; rm -f "$T/held"
  ( flock 9; touch "$T/held"; sleep 2; date +%s > "$T/base/last-reset"; echo "$1" > "$T/disk" ) 9>"$T/lock" &
  until [ -e "$T/held" ]; do sleep 0.1; done
  rc=0; out=$(bash "$R" --if-disk-above 80 2>&1) || rc=$?
  wait
  echo "$out"
  [ "$rc" -eq "$2" ] || { echo "FAIL: exit $rc, want $2"; exit 1; }
  [ ! -s "$T_LOG" ] || { echo "FAIL: reset ran:"; cat "$T_LOG"; exit 1; }
}

# Case 1a: the daily reset ended and the disk is fine again -> exit 0.
waiting_run 50 0
grep -q 'early reset not needed' <<< "$out" || { echo "FAIL: no skip message"; exit 1; }
echo "PASS 1a: disk back below the limit -> exit 0, no reset"

# Case 1b: the daily reset ended but the disk is still full -> the 6 h guard error, exit 1.
waiting_run 90 1
grep -q 'ERROR: / is 90% full but the last reset' <<< "$out" || { echo "FAIL: no guard error"; exit 1; }
echo "PASS 1b: disk still full after a reset -> ERROR, exit 1, no reset"

# Case 2 (control): last reset is old and nothing holds the lock, so the early reset runs.
echo 90 > "$T/disk"; echo 0 > "$T/base/last-reset"; : > "$T_LOG"
bash "$R" --if-disk-above 80 > /dev/null 2>&1
grep -q '^docker' "$T_LOG" || { echo "FAIL: control did not reset"; exit 1; }
echo "PASS 2: early reset still runs when no reset came first"
