#!/bin/bash
# iOS SDK gates (ADR-0047 D13). Usage: scripts/verify-ios.sh [all|lint|tsan|asan|analyze|manifest|api|api-update [artifact-dir]|available]
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
  # --follow-symlinks: Tests/SignalDockTests/TrackingPlan.swift is a link to the generated tracking plan (ADR-0047,
  # ticket 11); without the flag swift-format skips a link and exits 0.
  find "$PKG/Package.swift" "$PKG/Sources" "$PKG/Tests" -path "$PKG/Sources/SignalDock/Generated" -prune -o -name '*.swift' -print0 |
    xargs -0 swift format lint --strict --follow-symlinks
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
# module's .swiftinterface; sdk-ios/api/SignalDock.swiftinterface is that text, normalized, and must match it.
# The printing of an interface differs between compiler versions, so the compiler of CI (the pinned Xcode of
# .github/workflows/repo-rules.yml) is the single source of truth: sdk-ios/api/compiler-version holds its
# major.minor. Locally, a different compiler skips the check with a message. In CI (CI=true) a different compiler
# fails the check, and the actual interface and compiler version are written to $API_ACTUAL_DIR (default
# sdk-ios/build/api-actual) for the workflow to upload; `api-update <that directory>` takes them into sdk-ios/api/.
# The normalization drops what the compilers add or print differently without changing the API: comment lines
# (compiler version and flags), import lines, #if/#else/#endif lines, a leading `nonisolated ` on synthesized
# `allCases` and `rawValue`, and the `Module::Name` selector form of newer compilers (older ones print `Module.Name`).
# Warnings are not errors here: only the interface is wanted.
API_FILE="$PKG/api/SignalDock.swiftinterface"
API_COMPILER_FILE="$PKG/api/compiler-version"
API_ACTUAL_DIR="${API_ACTUAL_DIR:-$PKG/build/api-actual}"

compiler_version() { # major.minor of the selected Swift compiler
  xcrun swiftc --version 2> /dev/null | sed -n 's/.*Apple Swift version \([0-9][0-9]*\.[0-9][0-9]*\).*/\1/p' | head -1
}

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
  sed -E -e '/^\/\//d' -e '/^import /d' -e '/^[[:space:]]*#(if|else|elseif|endif)/d' \
    -e 's/^([[:space:]]*)nonisolated (public static var allCases)/\1\2/' \
    -e 's/^([[:space:]]*)nonisolated (public var rawValue)/\1\2/' \
    -e 's/([A-Za-z_][A-Za-z0-9_]*)::/\1./g' "$interface"
  rm -rf "$derived" # the disk of a small Mac is the limit
}

run_api() {
  echo "=== iOS: public API (.swiftinterface) against sdk-ios/api/ ==="
  local expected actual
  expected="$(tr -d '[:space:]' < "$API_COMPILER_FILE")"
  actual="$(compiler_version)"
  if [ "$actual" != "$expected" ] && [ "${CI:-}" != true ]; then
    echo "skip: the API file comes from Swift $expected (the compiler of CI) and this Mac has Swift ${actual:-unknown};" \
      "the check runs in CI (ios-test)"
    return 0
  fi
  local out="$API_ACTUAL_DIR"
  mkdir -p "$out"
  build_api > "$out/SignalDock.swiftinterface" || exit 1
  printf '%s\n' "$actual" > "$out/compiler-version"
  if [ "$actual" != "$expected" ]; then
    echo "error: the compiler is Swift $actual but sdk-ios/api/compiler-version says $expected." >&2
    echo "Take the interface of this compiler: download the api-actual artifact of this run and run" >&2
    echo "scripts/verify-ios.sh api-update <directory of the artifact>, then commit sdk-ios/api/." >&2
    exit 1
  fi
  if ! diff -u "$API_FILE" "$out/SignalDock.swiftinterface"; then
    echo "error: the public API of the iOS SDK differs from sdk-ios/api/SignalDock.swiftinterface" >&2
    echo "If the change is intended: take the api-actual artifact of the CI run with" >&2
    echo "scripts/verify-ios.sh api-update <directory of the artifact> (the CI compiler is the source of truth)," >&2
    echo "review the diff, and update the integration guide and the CHANGELOG in the same PR (ADR-0019 form)." >&2
    exit 1
  fi
  echo "public API unchanged (Swift $actual)"
}

# `api-update <directory>` copies SignalDock.swiftinterface and compiler-version from the CI artifact.
# Without a directory it builds with this Mac's compiler, which is right only when that is the compiler of CI.
run_api_update() {
  mkdir -p "$(dirname "$API_FILE")"
  if [ -n "${2:-}" ]; then
    cp "$2/SignalDock.swiftinterface" "$API_FILE"
    cp "$2/compiler-version" "$API_COMPILER_FILE"
  else
    echo "warning: using this Mac's compiler (Swift $(compiler_version)); the file must come from the compiler of CI" >&2
    build_api > "$API_FILE.new" || { rm -f "$API_FILE.new"; exit 1; }
    mv "$API_FILE.new" "$API_FILE"
    printf '%s\n' "$(compiler_version)" > "$API_COMPILER_FILE"
  fi
  echo "updated $API_FILE (Swift $(tr -d '[:space:]' < "$API_COMPILER_FILE"))"
}

case "${1:-all}" in
  lint) run_lint ;;
  tsan) echo "=== iOS: Simulator tests, Thread Sanitizer ==="; run_tests -enableThreadSanitizer YES ;;
  asan) echo "=== iOS: Simulator tests, ASan + UBSan ==="; run_tests -enableAddressSanitizer YES -enableUndefinedBehaviorSanitizer YES ;;
  analyze) run_analyze ;;
  manifest) run_manifest ;;
  api) run_api ;;
  api-update) run_api_update "$@" ;;
  all) run_lint; "$0" tsan; "$0" asan; run_analyze; run_manifest; run_api ;;
  available) available ;;
  *) echo "usage: $0 [all|lint|tsan|asan|analyze|manifest|api|api-update [artifact-dir]|available]" >&2; exit 2 ;;
esac
