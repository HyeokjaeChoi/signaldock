#!/bin/bash
# What the iOS SDK costs a host app (ADR-0047 amendment "ticket 16, measurement method"; the iOS form of ADR-0020).
# Full run: local only. macOS with full Xcode and an iOS Simulator runtime.
#
# Usage: scripts/measure-ios-sdk.sh [--size-only <out dir>] [path/to/signaldock-sdk-ios-X.Y.Z.zip]
# --size-only: part 1 only (the two archives and the size CSV files into <out dir>); no Simulator, no probe build, nothing
# written under perf/sdk-results. scripts/check-ios-size-budget.sh (CI job ios-size-budget, ADR-0047 ticket 17) uses it.
# Without an argument it builds a fresh release ZIP of HEAD (scripts/build-ios-release-zip.sh), like verify-consumer-ios.sh.
#
# Two arms of the independent consumer app compatibility/consumer-ios, both built in temporary copies (the checked-in app
# is not edited):
#   sdk    the SDK from the unzipped release ZIP
#   nosdk  perf/ios-host-cost/baseline: an empty package with the same public signatures, so the app sources are identical
# 1. Size: `xcodebuild archive`, Release, iphoneos arm64, CODE_SIGNING_ALLOWED=NO, with a link map. App folder, main binary,
#    Mach-O segments, an unencrypted IPA zip, and the SDK share of the binary from the link map.
# 2. Host cost on a Simulator: a separate measurement build (Release, iphonesimulator, arm64) of each arm. The script appends
#    perf/ios-host-cost/HostCostProbe.swift to ConsumerApp.swift in the copy; the probe times initialize() and track() on the
#    main thread. The probe is in no checked-in build. Cold start and memory are read from outside (simctl, footprint).
# Writes perf/sdk-results/ios-<sdk version>-<UTC date>/ (CSV files, meta.json, summary.md).
# Environment: IOSCOST_DEVICE (a Simulator UDID or name; default: a temporary Simulator that the script creates and deletes),
#   IOSCOST_RUNS (startup and idle-memory runs per arm, default 20), IOSCOST_BURST_RUNS (default 10),
#   IOSCOST_BURST (track() calls per burst run, default 500), IOSCOST_IDLE (seconds before memory is read, default 5),
#   IOSCOST_KEEP=1 (keep the temporary build folder, to debug).
set -euo pipefail

[ "$(uname)" = Darwin ] || { echo "error: this script needs macOS and Xcode" >&2; exit 1; }
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SIZE_ONLY=""
if [ "${1:-}" = --size-only ]; then
  [ -n "${2:-}" ] || { echo "error: --size-only needs an output directory" >&2; exit 1; }
  mkdir -p "$2"; SIZE_ONLY="$(cd "$2" && pwd)"; shift 2
fi
ZIP="${1:-}"
[ -z "$ZIP" ] || [ ! -f "$ZIP" ] || ZIP="$(cd "$(dirname "$ZIP")" && pwd)/$(basename "$ZIP")"  # absolute before the cd
cd "$ROOT"
RUNS="${IOSCOST_RUNS:-20}"
BURST_RUNS="${IOSCOST_BURST_RUNS:-10}"
BURST="${IOSCOST_BURST:-500}"
IDLE="${IOSCOST_IDLE:-5}"
[ -n "$SIZE_ONLY" ] || for n in RUNS BURST_RUNS BURST IDLE; do
  [[ "${!n}" =~ ^[1-9][0-9]*$ ]] || { echo "error: IOSCOST_$n must be a positive integer, got '${!n}'" >&2; exit 1; }
done
command -v xcodebuild > /dev/null && xcrun --sdk iphoneos --show-sdk-path > /dev/null \
  || { echo "error: full Xcode with the iOS SDK is needed" >&2; exit 1; }

VERSION_RE='s/^  public static let version = "\(.*\)"$/\1/p'
if [ -z "$ZIP" ]; then
  VERSION="$(git show HEAD:sdk-ios/Sources/SignalDock/SignalDockSDK.swift | sed -n "$VERSION_RE")"
  [ -n "$VERSION" ] || { echo "error: no SignalDockSDK.version at HEAD" >&2; exit 1; }
  echo "=== 0. build a fresh release ZIP ==="
  scripts/build-ios-release-zip.sh "$VERSION"
  ZIP="$ROOT/dist/signaldock-sdk-ios-$VERSION.zip"
fi
[ -f "$ZIP" ] || { echo "error: $ZIP does not exist" >&2; exit 1; }
# The version is the one inside the ZIP, also when the caller passes a ZIP.
VERSION="$(unzip -p "$ZIP" SignalDock/Sources/SignalDock/SignalDockSDK.swift | sed -n "$VERSION_RE")"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "error: '$VERSION' (version in $ZIP) is not X.Y.Z" >&2; exit 1; }

SIM=""
if [ -z "$SIZE_ONLY" ] && [ -n "${IOSCOST_DEVICE:-}" ]; then  # fail early, before any build
  SIM="$(python3 -I perf/ios_host_cost.py resolve "$IOSCOST_DEVICE")"
fi

WORK="$(mktemp -d)"
CREATED_SIM=""
BOOTED_BY_US=0
OURS=0  # set once the caller's Simulator is known to hold none of our bundle IDs
cleanup() {
  [ -n "$CREATED_SIM" ] && { xcrun simctl shutdown "$CREATED_SIM" 2> /dev/null || true; xcrun simctl delete "$CREATED_SIM" 2> /dev/null || true; }
  if [ -z "$CREATED_SIM" ] && [ -n "$SIM" ]; then  # a Simulator of the caller: remove our apps; shut it down only if we booted it
    [ "$OURS" = 1 ] && for id in local.signaldock.consumerios local.signaldock.consumerios.nosdk; do xcrun simctl uninstall "$SIM" "$id" 2> /dev/null || true; done
    [ "$BOOTED_BY_US" = 1 ] && { xcrun simctl shutdown "$SIM" 2> /dev/null || true; }
  fi
  if [ "${IOSCOST_KEEP:-0}" = 1 ]; then echo "kept: $WORK"; else rm -rf "$WORK"; fi
}
trap cleanup EXIT

# copy_app <name> <sdk|nosdk> <probe 0|1>: a temporary copy of the app at $WORK/<name>/compatibility/consumer-ios. The
# project references ../../tracking-plan/generated/swift/TrackingPlan.swift, so that file keeps its relative place.
copy_app() {
  local dir="$WORK/$1/compatibility/consumer-ios"
  mkdir -p "$dir/.sdk" "$WORK/$1/tracking-plan/generated/swift"
  cp -R compatibility/consumer-ios/ConsumerIOS.xcodeproj compatibility/consumer-ios/Sources "$dir/"
  cp tracking-plan/generated/swift/TrackingPlan.swift "$WORK/$1/tracking-plan/generated/swift/"
  if [ "$2" = sdk ]; then
    unzip -q "$ZIP" -d "$dir/.sdk"
  else
    mkdir -p "$dir/.sdk/SignalDock"
    cp -R perf/ios-host-cost/baseline/. "$dir/.sdk/SignalDock/"
  fi
  [ -f "$dir/.sdk/SignalDock/Package.swift" ] || { echo "error: no package at $dir/.sdk/SignalDock" >&2; exit 1; }
  if [ "$3" = 1 ]; then
    python3 -I - "$dir/Sources/ConsumerApp.swift" perf/ios-host-cost/HostCostProbe.swift << 'PY'
import sys
app, probe = sys.argv[1], sys.argv[2]
s = open(app).read()
for old, new in [("RootView().environmentObject(model)", "RootView().environmentObject(model).onAppear { HostCostProbe.firstView() }"),
                 ("SignalDockSDK.initialize(config)", "HostCostProbe.initialize(config)")]:
    if s.count(old) != 1:
        sys.exit(f"error: {app} must contain '{old}' exactly once; the probe patch in scripts/measure-ios-sdk.sh needs an update")
    s = s.replace(old, new)
open(app, "w").write(s + "\n" + open(probe).read())
PY
  fi
}

echo "=== 1. size: archive Release iphoneos arm64, unsigned, both arms ==="
for arm in sdk nosdk; do
  copy_app "size-$arm" "$arm" 0
  (cd "$WORK/size-$arm/compatibility/consumer-ios" && xcodebuild archive -project ConsumerIOS.xcodeproj -scheme ConsumerIOS \
    -configuration Release -destination 'generic/platform=iOS' -archivePath "$WORK/size-$arm.xcarchive" \
    -derivedDataPath "$WORK/size-$arm/dd" ARCHS=arm64 CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO \
    LD_GENERATE_MAP_FILE=YES "LD_MAP_FILE_PATH=$WORK/linkmap-$arm-"'$(TARGET_NAME).txt' > "$WORK/size-$arm.log" 2>&1) \
    || { tail -30 "$WORK/size-$arm.log" >&2; echo "error: archive of the $arm arm failed" >&2; exit 1; }
  [ -d "$WORK/size-$arm.xcarchive/Products/Applications/ConsumerIOS.app" ] || { echo "error: no .app in the $arm archive" >&2; exit 1; }
  echo "  $arm archive ok"
done

if [ -n "$SIZE_ONLY" ]; then
  python3 -I perf/ios_host_cost.py size "$WORK" "$SIZE_ONLY"
  echo "ok: size files in $SIZE_ONLY"
  exit 0
fi

echo "=== 2. host cost: measurement builds (Release, iphonesimulator, arm64) ==="
for arm in sdk nosdk; do
  copy_app "probe-$arm" "$arm" 1
  [ "$arm" = nosdk ] && suffix=".nosdk" || suffix=""
  (cd "$WORK/probe-$arm/compatibility/consumer-ios" && xcodebuild build -project ConsumerIOS.xcodeproj -scheme ConsumerIOS \
    -configuration Release -destination 'generic/platform=iOS Simulator' -derivedDataPath "$WORK/probe-$arm/dd" \
    ARCHS=arm64 CODE_SIGNING_ALLOWED=NO "PRODUCT_BUNDLE_IDENTIFIER=local.signaldock.consumerios$suffix" \
    > "$WORK/probe-$arm.log" 2>&1) || { tail -30 "$WORK/probe-$arm.log" >&2; echo "error: probe build of the $arm arm failed" >&2; exit 1; }
  echo "  $arm probe build ok"
done

if [ -z "$SIM" ]; then
  runtime="$(xcrun simctl list runtimes available -j | python3 -I -c 'import json,sys; r=[x for x in json.load(sys.stdin)["runtimes"] if x["platform"]=="iOS"]; print(sorted(r,key=lambda x:[int(p) for p in x["version"].split(".")])[-1]["identifier"])')"
  devtype="$(xcrun simctl list devicetypes -j | python3 -I -c 'import json,re,sys; t=[d["identifier"] for d in json.load(sys.stdin)["devicetypes"] if re.search(r"iPhone-\d+-Pro$", d["identifier"])]; print(max(t, key=lambda i: int(re.search(r"iPhone-(\d+)", i).group(1))))')"
  SIM="$(xcrun simctl create "signaldock-ioscost-$$" "$devtype" "$runtime")"
  CREATED_SIM="$SIM"
fi
xcrun simctl list devices | grep "$SIM" | grep -q Booted || BOOTED_BY_US=1
xcrun simctl boot "$SIM" 2> /dev/null || true
xcrun simctl bootstatus "$SIM" -b > /dev/null
if [ -z "$CREATED_SIM" ]; then  # the apps are uninstalled at the end: never touch an app of the caller
  for id in local.signaldock.consumerios local.signaldock.consumerios.nosdk; do
    if xcrun simctl get_app_container "$SIM" "$id" > /dev/null 2>&1; then
      echo "error: $id is already installed on $SIM; the script would replace and then remove it. Uninstall it or use another Simulator" >&2
      exit 1
    fi
  done
  OURS=1
fi
echo "Simulator: $SIM"

OUT="perf/sdk-results/ios-$VERSION-$(date -u +%Y%m%d)"
[ "$(dirname "$OUT")" = perf/sdk-results ] || { echo "error: refusing to remove $OUT" >&2; exit 1; }
rm -rf "$OUT"
mkdir -p "$OUT"
python3 -I perf/ios_host_cost.py size "$WORK" "$OUT"
python3 -I perf/ios_host_cost.py run "$WORK" "$OUT" "$SIM" "$RUNS" "$BURST_RUNS" "$BURST" "$IDLE" "$VERSION" "$ZIP"
echo "ok: results in $OUT"
