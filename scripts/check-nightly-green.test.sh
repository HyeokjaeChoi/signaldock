#!/bin/bash
# Test of scripts/check-nightly-green.sh with a stub `gh` (ADR-0049 D4).
set -euo pipefail
SCRIPT="$(cd "$(dirname "$0")" && pwd)/check-nightly-green.sh"
T="$(mktemp -d)"
trap 'rm -rf "$T"' EXIT
export GITHUB_SHA=abc GITHUB_REPOSITORY=o/r
fail=0
expect() { # expect <0|1> <name> <stub output> <stub exit code> [text the error must contain]
  local want="$1" name="$2" rc=0 err
  printf '#!/bin/bash\necho %q\nexit %s\n' "$3" "$4" > "$T/gh"
  chmod +x "$T/gh"
  err="$(PATH="$T:$PATH" "$SCRIPT" 2>&1 > /dev/null)" || rc=$?
  if [ "$rc" != "$want" ]; then echo "FAIL: $name (exit $rc, want $want)"; fail=1
  elif [ -n "${5:-}" ] && ! grep -qF "$5" <<< "$err"; then echo "FAIL: $name (message lacks '$5')"; fail=1
  else echo "ok: $name"; fi
}
expect 0 "success" "success https://x/1" 0
expect 1 "failure" "failure https://x/1" 0
expect 1 "cancelled" "cancelled https://x/1" 0
expect 1 "still running" "in_progress https://x/1" 0
expect 1 "no run" "none" 0 "no nightly.yml run"
expect 1 "API error names the permission" "HTTP 403" 1 "actions: read"
exit "$fail"
