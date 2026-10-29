#!/bin/bash
# iOS ticket 14 (ADR-0047 D12, D13 item 9): build the release ZIP (or take one), unzip it into a clean folder, and
# build the independent consumer app compatibility/consumer-ios in Debug and Release for the iOS Simulator from that
# folder only. The iOS form of scripts/verify-consumer.sh. No Simulator run is needed (this is a build check).
# Usage: scripts/verify-consumer-ios.sh [path/to/signaldock-sdk-ios-X.Y.Z.zip]
# Without an argument it runs scripts/build-ios-release-zip.sh for SignalDockSDK.version of HEAD (a fresh ZIP).
# The unzipped folder is placed at compatibility/consumer-ios/.sdk/SignalDock, the path the Xcode project references
# (a local package). A caller never needs to put sdk-ios/ on any path; the app has no reference to it.
# CI sets IOS_WERROR=1 (warnings as errors, like scripts/verify-ios.sh).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/compatibility/consumer-ios"
WERROR=()
if [ "${IOS_WERROR:-0}" = 1 ]; then
  WERROR=(SWIFT_TREAT_WARNINGS_AS_ERRORS=YES GCC_TREAT_WARNINGS_AS_ERRORS=YES)
fi

ZIP="${1:-}"
if [ -z "$ZIP" ]; then
  echo "=== 1. build a fresh release ZIP ==="
  VERSION="$(git -C "$ROOT" show HEAD:sdk-ios/Sources/SignalDock/SignalDockSDK.swift | sed -n 's/^  public static let version = "\(.*\)"$/\1/p')"
  [ -n "$VERSION" ] || { echo "error: no SignalDockSDK.version at HEAD" >&2; exit 1; }
  "$ROOT/scripts/build-ios-release-zip.sh" "$VERSION"
  ZIP="$ROOT/dist/signaldock-sdk-ios-$VERSION.zip"
fi
[ -f "$ZIP" ] || { echo "error: $ZIP does not exist" >&2; exit 1; }

echo "=== 2. unzip into compatibility/consumer-ios/.sdk ==="
rm -rf "$APP/.sdk" "$APP/build"
mkdir -p "$APP/.sdk"
unzip -q "$ZIP" -d "$APP/.sdk"
[ -f "$APP/.sdk/SignalDock/Package.swift" ] || { echo "error: the ZIP has no SignalDock/Package.swift" >&2; exit 1; }

for CONFIG in Debug Release; do
  echo "=== 3. build consumer-ios $CONFIG (iOS Simulator) from the unzipped package ==="
  (cd "$APP" && xcodebuild build -project ConsumerIOS.xcodeproj -scheme ConsumerIOS -configuration "$CONFIG" \
    -destination 'generic/platform=iOS Simulator' -derivedDataPath "$APP/build/DerivedData" \
    ${WERROR[@]+"${WERROR[@]}"})
  APP_PATH="$APP/build/DerivedData/Build/Products/$CONFIG-iphonesimulator/ConsumerIOS.app"
  [ -d "$APP_PATH" ] || { echo "error: no $APP_PATH" >&2; exit 1; }
  # The SDK must come from the unzipped package, and its privacy manifest must be in the app (ADR-0047 D14).
  test -n "$(find "$APP_PATH" -path '*SignalDock_SignalDock.bundle/PrivacyInfo.xcprivacy' | head -1)" \
    || { echo "error: PrivacyInfo.xcprivacy is not in the SignalDock resource bundle inside $APP_PATH" >&2; exit 1; }
  echo "APP ($CONFIG): $APP_PATH"
done

echo "ZIP: $ZIP"
echo "ok: consumer-ios builds in Debug and Release from the unzipped package"
