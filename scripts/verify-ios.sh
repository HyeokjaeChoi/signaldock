#!/bin/bash
# iOS SDK gates (ADR-0047 D13). Usage: scripts/verify-ios.sh [all|lint|tsan|asan|analyze|manifest|api|api-update|available]
# `available` exits 0 only with full Xcode (Swift 6, `swift format`) and a Simulator runtime that pick_simulator can select
# (iOS 15 or later, with an iPhone); a Command Line Tools shim of xcodebuild is not enough.
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

# Prints a Simulator UDID: the first iPhone of the oldest installed iOS runtime at iOS 15 or later (the deployment target).
pick_simulator() {
  xcrun simctl list runtimes >&2
  xcrun simctl list devices available -j | python3 -I -c '
import json, re, sys
data = json.load(sys.stdin)["devices"]
runs = []
for key, devs in data.items():
    m = re.search(r"iOS-(\d+)-(\d+)", key)
    phones = [d for d in devs if d["name"].startswith("iPhone") and d.get("isAvailable", True)]
    if m and int(m.group(1)) >= 15 and phones:
        runs.append((int(m.group(1)), int(m.group(2)), phones[0]["udid"]))
if not runs:
    sys.exit("error: no iOS 15 or later Simulator runtime with an iPhone")
runs.sort()
major, minor, udid = runs[0]
print(f"simulator runtime: iOS {major}.{minor} (oldest installed)", file=sys.stderr)
if major != 15:
    print("note: no iOS 15 runtime; deployment target 15 builds, tests run on the oldest runtime (ADR-0047 fallback)", file=sys.stderr)
print(udid)'
}

run_lint() {
  echo "=== iOS: swift format lint --strict ==="
  # Sources/SignalDock/Generated is generator output (internal, 4-space, no documentation): the generator pins its
  # format and the drift check in verify-contract.sh guards it, so the lint skips that folder (ADR-0047 D10 amendment).
  find "$PKG/Package.swift" "$PKG/Sources" "$PKG/Tests" -path "$PKG/Sources/SignalDock/Generated" -prune -o -name '*.swift' -print0 |
    xargs -0 swift format lint --strict
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

# Same selection as the tests use, so a skip here never turns into a failure later (spec D13).
available() {
  xcodebuild -version > /dev/null 2>&1 || return 1
  [ "$(swift --version 2>&1 | sed -n 's/.*Swift version \([0-9][0-9]*\).*/\1/p' | head -1)" -ge 6 ] 2> /dev/null || return 1
  swift format --version > /dev/null 2>&1 || return 1
  pick_simulator > /dev/null 2>&1
}

# The built package must carry the privacy manifest in its resource bundle (ADR-0047 D14).
run_manifest() {
  echo "=== iOS: privacy manifest in the built bundle ==="
  rm -rf "$DERIVED/Build/Products" # a stale bundle from an earlier run must not pass
  (cd "$PKG" && xcodebuild build -scheme SignalDock -destination 'generic/platform=iOS Simulator' \
    -derivedDataPath "$DERIVED" ${WERROR[@]+"${WERROR[@]}"})
  local found
  found="$(find "$DERIVED/Build/Products" -name PrivacyInfo.xcprivacy -path '*/SignalDock_SignalDock.bundle/PrivacyInfo.xcprivacy' | head -1)"
  if [ -z "$found" ]; then
    echo "error: PrivacyInfo.xcprivacy is not in a SignalDock resource bundle under $DERIVED/Build/Products" >&2
    exit 1
  fi
  plutil -lint "$found"
  echo "found: $found"
}

# Public API check (ADR-0047 D13 item 7, iOS form of ADR-0019). A check-only build with library evolution writes the
# module's .swiftinterface; sdk-ios/api/SignalDock.swiftinterface is that text, normalized, and must match it. The
# normalization drops what differs between compiler versions without changing the API: comment lines (compiler version
# and flags), import lines (the compiler adds some), and the `Module::Name` selector form of newer compilers, which
# older ones print as `Module.Name`. Warnings are not errors here: only the interface is wanted.
API_FILE="$PKG/api/SignalDock.swiftinterface"

build_api() { # prints the normalized interface
  local derived="$PKG/build/ApiDerived" log interface
  log="$(mktemp)"
  rm -rf "$derived"
  if ! (cd "$PKG" && xcodebuild build -scheme SignalDock -destination 'generic/platform=iOS Simulator' \
    -derivedDataPath "$derived" ARCHS=arm64 ONLY_ACTIVE_ARCH=NO BUILD_LIBRARY_FOR_DISTRIBUTION=YES) > "$log" 2>&1; then
    tail -40 "$log" >&2
    rm -f "$log"
    echo "error: the check-only build for the public API failed" >&2
    return 1
  fi
  rm -f "$log"
  interface="$(find "$derived/Build/Products" -name 'arm64-apple-ios-simulator.swiftinterface' | head -1)"
  if [ -z "$interface" ]; then
    echo "error: no arm64-apple-ios-simulator.swiftinterface under $derived/Build/Products" >&2
    return 1
  fi
  sed -E -e '/^\/\//d' -e '/^import /d' -e 's/([A-Za-z_][A-Za-z0-9_]*)::/\1./g' "$interface"
  rm -rf "$derived" # the disk of a small Mac is the limit
}

run_api() {
  echo "=== iOS: public API (.swiftinterface) against sdk-ios/api/ ==="
  local actual
  actual="$(mktemp)"
  build_api > "$actual" || { rm -f "$actual"; exit 1; }
  if ! diff -u "$API_FILE" "$actual"; then
    rm -f "$actual"
    echo "error: the public API of the iOS SDK differs from sdk-ios/api/SignalDock.swiftinterface" >&2
    echo "If the change is intended: run scripts/verify-ios.sh api-update, review the diff, and update the integration" >&2
    echo "guide and the CHANGELOG in the same PR (ADR-0019 form)." >&2
    exit 1
  fi
  rm -f "$actual"
  echo "public API unchanged"
}

run_api_update() {
  mkdir -p "$(dirname "$API_FILE")"
  build_api > "$API_FILE.new" || { rm -f "$API_FILE.new"; exit 1; }
  mv "$API_FILE.new" "$API_FILE"
  echo "updated $API_FILE"
}

case "${1:-all}" in
  lint) run_lint ;;
  tsan) echo "=== iOS: Simulator tests, Thread Sanitizer ==="; run_tests -enableThreadSanitizer YES ;;
  asan) echo "=== iOS: Simulator tests, ASan + UBSan ==="; run_tests -enableAddressSanitizer YES -enableUndefinedBehaviorSanitizer YES ;;
  analyze) run_analyze ;;
  manifest) run_manifest ;;
  api) run_api ;;
  api-update) run_api_update ;;
  all) run_lint; "$0" tsan; "$0" asan; run_analyze; run_manifest; run_api ;;
  available) available ;;
  *) echo "usage: $0 [all|lint|tsan|asan|analyze|manifest|api|api-update|available]" >&2; exit 2 ;;
esac
