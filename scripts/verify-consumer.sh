#!/bin/bash
# Issue 06: build the Maven ZIP, unzip it into a clean folder, and build the independent
# consumer app (R8 release) from that folder only. No emulator needed.
# The ZIP, the unzipped repository folder, and the minified APK paths are printed at the end.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

if [ -z "${JAVA_HOME:-}" ]; then
  export JAVA_HOME="$ROOT/.tools/jdk-21.0.12.1+1"
fi
if [ ! -x "$JAVA_HOME/bin/java" ]; then
  echo "error: no JDK at $JAVA_HOME; run scripts/setup-toolchain.sh or set JAVA_HOME" >&2
  exit 1
fi
if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ]; then
  echo "error: set ANDROID_HOME to the Android SDK (platform android-36)" >&2
  exit 1
fi

echo "=== 1. publish and zip local.signaldock:sdk-android:0.1.0 ==="
"$ROOT/sdk/gradlew" -p "$ROOT/sdk" :sdk-android:signaldockMavenZip --no-daemon
ZIP="$ROOT/sdk/sdk-android/build/distributions/signaldock-sdk-android-0.1.0-maven.zip"

echo "=== 2. unzip into a clean folder ==="
REPO="${SIGNALDOCK_REPO_DIR:-$(mktemp -d)/maven-repo}"
rm -rf "$REPO"
mkdir -p "$REPO"
unzip -q "$ZIP" -d "$REPO"

echo "=== 2b. AAR Kotlin metadata must be 2.2 or lower (spec Q132) ==="
# AGP 9 built-in Kotlin is 2.2. A higher metadata version breaks consumers with no Kotlin override.
META="$(mktemp -d)"
unzip -q "$REPO/local/signaldock/sdk-android/0.1.0/sdk-android-0.1.0.aar" classes.jar -d "$META"
(cd "$META" && unzip -q classes.jar -d classes)
MVS="$(cd "$META/classes" && find . -name '*.class' -print0 \
  | xargs -0 "$JAVA_HOME/bin/javap" -v -cp . | grep -o 'mv=\[[0-9,]*\]' | sort -u)"
echo "metadata versions: $MVS"
[ -n "$MVS" ] || { echo "error: no kotlin.Metadata found in the AAR" >&2; exit 1; }
if echo "$MVS" | grep -qv -E 'mv=\[2,[0-2],[0-9]+\]'; then
  echo "error: AAR Kotlin metadata is above 2.2" >&2
  exit 1
fi
rm -rf "$META"

echo "=== 3. consumer assembleRelease (R8) from the unzipped folder ==="
# Effective Kotlin Gradle plugin must be AGP 9's default (2.2.x). Catches overrides in any form.
KGP="$("$ROOT/compatibility/consumer-android/gradlew" -p "$ROOT/compatibility/consumer-android" \
  -Psignaldock.repo="$REPO" buildEnvironment --no-daemon -q | grep -o 'kotlin-gradle-plugin:[0-9][0-9.]*' | sort -u)"
echo "consumer Kotlin Gradle plugin: $KGP"
if [ "$KGP" != "kotlin-gradle-plugin:2.2.10" ] && ! echo "$KGP" | grep -qx 'kotlin-gradle-plugin:2\.2\.[0-9]*'; then
  echo "error: consumer Kotlin is not AGP's default 2.2.x; revisit Q132 if the AGP default moved" >&2
  exit 1
fi
"$ROOT/compatibility/consumer-android/gradlew" -p "$ROOT/compatibility/consumer-android" \
  -Psignaldock.repo="$REPO" :app:assembleRelease --no-daemon

echo "ZIP:  $ZIP"
echo "REPO: $REPO"
echo "APK:  $ROOT/compatibility/consumer-android/app/build/outputs/apk/release/app-release.apk"
