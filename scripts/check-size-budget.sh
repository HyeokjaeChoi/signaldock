#!/bin/bash
# APK size budget gate (issue 40, ADR-0023). No emulator needed.
#
# Builds the independent consumer app (R8 release) from the Maven ZIP twice, with the SDK and as the baseline with
# -Psignaldock.sdk=false (ADR-0020), prints the APK size delta, and fails when the delta is above BUDGET_BYTES.
#
# To raise the budget on purpose: change BUDGET_BYTES below in the same PR that adds the size, and in that PR
# update CHANGELOG.md [Unreleased] and the budget section of docs/adr/0023-ci-device-tests-and-size-budget.md
# (reason and new measured delta). Do not raise it only to make the job pass.
set -euo pipefail

# Measured delta on the API 37 run was 616,829 bytes (ADR-0020). 700,000 is about 13 % above that: room for
# a different R8 or zip result on the CI runner, and for small SDK growth, but not for a new large dependency.
BUDGET_BYTES=700000

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [ -z "${JAVA_HOME:-}" ]; then
  export JAVA_HOME="$ROOT/.tools/$(ls "$ROOT/.tools" | grep '^jdk-')"
fi
[ -x "$JAVA_HOME/bin/java" ] || { echo "error: no JDK at $JAVA_HOME; run scripts/setup-toolchain.sh or set JAVA_HOME" >&2; exit 1; }
[ -n "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ] || { echo "error: set ANDROID_HOME to the Android SDK (platform android-36)" >&2; exit 1; }

VERSION="$(sed -n 's/^val sdkVersion = "\(.*\)"$/\1/p' "$ROOT/sdk/sdk-android/build.gradle.kts")"
[ -n "$VERSION" ] || { echo "error: no sdkVersion in sdk/sdk-android/build.gradle.kts" >&2; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
CONSUMER="$ROOT/compatibility/consumer-android"

echo "=== 1. Maven ZIP ==="
"$ROOT/sdk/gradlew" -p "$ROOT/sdk" :sdk-android:signaldockMavenZip --no-daemon -q
mkdir -p "$WORK/repo"
unzip -q "$ROOT/sdk/sdk-android/build/distributions/signaldock-sdk-android-$VERSION-maven.zip" -d "$WORK/repo"

echo "=== 2. consumer app, R8 release, with and without the SDK ==="
for v in sdk nosdk; do
  [ "$v" = nosdk ] && flag=false || flag=true
  "$CONSUMER/gradlew" -p "$CONSUMER" -Psignaldock.repo="$WORK/repo" -Psignaldock.version="$VERSION" \
    -Psignaldock.sdk=$flag --no-daemon -q :app:assembleRelease
  cp "$CONSUMER/app/build/outputs/apk/release/app-release.apk" "$WORK/$v.apk"
done

with="$(wc -c < "$WORK/sdk.apk" | tr -d ' ')"
without="$(wc -c < "$WORK/nosdk.apk" | tr -d ' ')"
delta=$((with - without))
echo "APK without SDK: $without bytes"
echo "APK with SDK:    $with bytes"
echo "delta:           $delta bytes (budget $BUDGET_BYTES, ADR-0023)"
[ "$delta" -gt 0 ] || { echo "FAIL: delta is not positive; the baseline build is wrong" >&2; exit 1; }
if [ "$delta" -gt "$BUDGET_BYTES" ]; then
  echo "FAIL: the SDK adds $((delta - BUDGET_BYTES)) bytes more than the budget. Remove the cause, or raise BUDGET_BYTES on purpose (see the top of this script)." >&2
  exit 1
fi
echo "OK: within budget"
