#!/bin/bash
# iOS size budget gate (ADR-0047 amendment "ticket 17, size budget"; the iOS form of scripts/check-size-budget.sh, ADR-0023).
# No Simulator needed. Runs scripts/measure-ios-sdk.sh --size-only (consumer app, Release, iphoneos arm64, unsigned, with the
# SDK from the release ZIP and with the empty stand-in), prints the deltas, and fails when one is above its budget.
# Usage: scripts/check-ios-size-budget.sh [path/to/signaldock-sdk-ios-X.Y.Z.zip]
#
# To raise a budget on purpose: change the constant below in the same PR that adds the size, and update the ticket 17
# amendment in docs/adr/0047-ios-sdk.md (reason and new measured delta), and CHANGELOG.md [Unreleased]. Do not raise it only to make the job pass.
set -euo pipefail

# Ticket 16 measured 153,303 (.ipa form) and 388,464 (main binary) with Xcode 27.1; CI (Xcode 26.6) measured 158,012 and 390,112 (ADR-0047 ticket 17 amendment).
IPA_BUDGET_BYTES=175000
BINARY_BUDGET_BYTES=440000

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
"$ROOT/scripts/measure-ios-sdk.sh" --size-only "$OUT" "$@"

fail=0
check() {  # <metric> <budget>
  local row delta
  row="$(grep "^$1," "$OUT/size.csv")" || { echo "error: no $1 in size.csv" >&2; exit 1; }
  delta="${row##*,}"
  [[ "$delta" =~ ^-?[0-9]+$ ]] || { echo "error: bad delta '$delta' for $1 in size.csv" >&2; exit 1; }
  echo "$1: $(echo "$row" | awk -F, '{print "without SDK " $2 ", with SDK " $3}')"
  echo "delta $1: $delta bytes (budget $2, ADR-0047 ticket 17)"
  if [ "$delta" -le 0 ]; then
    echo "FAIL: $1 delta is not positive; the baseline build is wrong" >&2
    fail=1
  elif [ "$delta" -gt "$2" ]; then
    echo "FAIL: the SDK adds $((delta - $2)) bytes ($1) more than the budget. Remove the cause, or raise the budget on purpose (see the top of this script)." >&2
    fail=1
  fi
}
check ipa_bytes "$IPA_BUDGET_BYTES"
check binary_bytes "$BINARY_BUDGET_BYTES"
[ "$fail" = 0 ] || exit 1
echo "OK: within budget"
