#!/bin/bash
# iOS SDK gates (ADR-0047 D13). Usage: scripts/verify-ios.sh [all|lint|tsan|asan|analyze]
# CI sets IOS_WERROR=1 (warnings as errors in CI only). The tests run on the oldest iOS Simulator runtime
# that is installed, because the GitHub runner has no iOS 15 runtime (spec section 5; ADR-0047 "iOS 15 Simulator fallback").
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG="$ROOT/sdk-ios"
DERIVED="$PKG/build/DerivedData" # build/ is git-ignored
WERROR=()
if [ "${IOS_WERROR:-0}" = 1 ]; then
  WERROR=(SWIFT_TREAT_WARNINGS_AS_ERRORS=YES GCC_TREAT_WARNINGS_AS_ERRORS=YES)
fi

# Prints a Simulator UDID: the first iPhone of the oldest installed iOS runtime.
pick_simulator() {
  xcrun simctl list runtimes >&2
  xcrun simctl list devices available -j | python3 -I -c '
import json, re, sys
data = json.load(sys.stdin)["devices"]
runs = []
for key, devs in data.items():
    m = re.search(r"iOS-(\d+)-(\d+)", key)
    phones = [d for d in devs if d["name"].startswith("iPhone") and d.get("isAvailable", True)]
    if m and phones:
        runs.append((int(m.group(1)), int(m.group(2)), phones[0]["udid"]))
if not runs:
    sys.exit("error: no iOS Simulator runtime with an iPhone")
runs.sort()
major, minor, udid = runs[0]
print(f"simulator runtime: iOS {major}.{minor} (oldest installed)", file=sys.stderr)
if major != 15:
    print("note: no iOS 15 runtime; deployment target 15 builds, tests run on the oldest runtime (ADR-0047 fallback)", file=sys.stderr)
print(udid)'
}

run_lint() {
  echo "=== iOS: swift format lint --strict ==="
  swift format lint --strict --recursive "$PKG/Package.swift" "$PKG/Sources" "$PKG/Tests"
}

run_tests() { # arguments: sanitizer flags
  local udid
  udid="$(pick_simulator)"
  (cd "$PKG" && xcodebuild test -scheme SignalDock -destination "id=$udid" \
    -derivedDataPath "$DERIVED" "$@" ${WERROR[@]+"${WERROR[@]}"})
}

run_analyze() {
  echo "=== iOS: xcodebuild analyze ==="
  (cd "$PKG" && xcodebuild analyze -scheme SignalDock -destination 'generic/platform=iOS Simulator' \
    -derivedDataPath "$DERIVED" ${WERROR[@]+"${WERROR[@]}"})
}

case "${1:-all}" in
  lint) run_lint ;;
  tsan) echo "=== iOS: Simulator tests, Thread Sanitizer ==="; run_tests -enableThreadSanitizer YES ;;
  asan) echo "=== iOS: Simulator tests, ASan + UBSan ==="; run_tests -enableAddressSanitizer YES -enableUndefinedBehaviorSanitizer YES ;;
  analyze) run_analyze ;;
  all) run_lint; "$0" tsan; run_analyze ;;
  *) echo "usage: $0 [all|lint|tsan|asan|analyze]" >&2; exit 2 ;;
esac
