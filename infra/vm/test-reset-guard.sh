#!/bin/bash
# Harness for signaldock-reset.sh --if-disk-above (ticket 10). Runs in a container with fake docker/git/curl/df/setpriv:
#   docker run --rm -v "$PWD":/repo:ro ubuntu:24.04 bash /repo/infra/vm/test-reset-guard.sh
# shellcheck disable=SC2016  # the fakes expand their variables when they run, not here
set -euo pipefail

T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
mkdir -p "$T/bin" "$T/base/src/scripts"
for c in git curl docker python3; do printf '#!/bin/bash\necho "%s $*" >> "$T_LOG"\n' "$c" > "$T/bin/$c"; done
printf '#!/bin/bash\necho "Use%%"; echo " 90%%"\n' > "$T/bin/df"
printf '#!/bin/bash\nwhile [[ $1 == --* ]]; do shift; done\nexec "$@"\n' > "$T/bin/setpriv"
: > "$T/base/src/scripts/demo-seed.py"
echo demo-v1.0.0 > "$T/base/current-tag"; echo SIGNALDOCK_COLLECTION_KEY=k > "$T/base/.env"
chmod +x "$T"/bin/*
export PATH="$T/bin:$PATH" T_LOG="$T/calls" SIGNALDOCK_BASE="$T/base" SIGNALDOCK_LOCK="$T/lock" SIGNALDOCK_READY_WAIT=5
R=$(dirname "$0")/signaldock-reset.sh

# Case 1: the disk-check run waits on the lock; the daily reset ends meanwhile and writes last-reset.
echo 0 > "$T/base/last-reset"; : > "$T_LOG"
( flock 9; sleep 2; date +%s > "$T/base/last-reset" ) 9>"$T/lock" &
sleep 1
rc=0; out=$(bash "$R" --if-disk-above 80 2>&1) || rc=$?
wait
echo "$out"
[ "$rc" -eq 0 ] || { echo "FAIL: exit $rc, want 0"; exit 1; }
[ ! -s "$T_LOG" ] || { echo "FAIL: reset ran:"; cat "$T_LOG"; exit 1; }
echo "PASS: waiting disk-check run exited 0 without a reset"

# Case 2 (control): last reset is old and nothing holds the lock, so the early reset runs.
echo 0 > "$T/base/last-reset"
bash "$R" --if-disk-above 80 > /dev/null 2>&1
grep -q '^docker' "$T_LOG" || { echo "FAIL: control did not reset"; exit 1; }
echo "PASS: early reset still runs when no reset came first"
