#!/bin/bash
# Issue 68 (ADR-0038): add the SHA-256 checksums of new Gradle dependencies to
# <build>/gradle/verification-metadata.xml in the four Gradle builds.
#
# Run it after you change gradle/libs.versions.toml, a plugin, or a dependency, and commit the result.
# It runs the same tasks that CI runs, with `--write-verification-metadata sha256`, so every
# configuration that CI resolves is covered. It uses an empty Gradle home, so it downloads everything again.
# Gradle only adds entries: to drop stale entries, delete the <components> block of one file and run
# the script again.
# Check the diff: a new entry for an artifact you did not change is a reason to stop and ask.
# It runs `:sdk-android:dependencyGuardBaseline` in place of `dependencyGuard`: the two tasks resolve the same
# configurations, and a stale baseline (ADR-0033) does not stop the run. It rewrites sdk/sdk-android/dependencies/
# when the SDK release classpath changed: commit that change and add a CHANGELOG.md line (docs/releases/README.md).
# Needs JAVA_HOME (or .tools/jdk-*) and ANDROID_HOME. No emulator.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [ -z "${JAVA_HOME:-}" ]; then
  export JAVA_HOME="$ROOT/.tools/$(ls "$ROOT/.tools" | grep '^jdk-21')"
fi
[ -x "$JAVA_HOME/bin/java" ] || { echo "error: no JDK at $JAVA_HOME; run scripts/setup-toolchain.sh or set JAVA_HOME" >&2; exit 1; }
[ -n "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ] || { echo "error: set ANDROID_HOME to the Android SDK (platforms android-36 and android-37; the demo app builds at 37)" >&2; exit 1; }

# Each Gradle run gets its own empty Gradle home. Gradle records only the files that it downloads: with a warm
# cache it skips files such as BOMs and parent POMs that an earlier run cached, and CI (a cold cache) fails on them.
GUH_ROOT="$(mktemp -d)"
trap 'rm -rf "$GUH_ROOT" "${REPO:+$(dirname "$REPO")}"' EXIT
W="--write-verification-metadata sha256 --no-daemon --no-build-cache -q"
gw() { # gw <build dir> <gradle arguments>
  local dir="$1"; shift
  GRADLE_USER_HOME="$(mktemp -d "$GUH_ROOT/home.XXXXXX")" "$dir/gradlew" -p "$dir" $W "$@"
}

echo "=== sdk ==="
# The SDK build, the tasks of the sdk-test, codeql and verify-contract jobs, and the Maven ZIP.
# `build` runs `dependencyGuard`, which fails on a stale baseline. `-x :sdk-android:dependencyGuard` skips it, and
# `dependencyGuardBaseline` below refreshes the baseline.
# `build` covers :profile-app and :baseline-profile (Baseline Profile plugin variants, ADR-0040) except the
# connected tasks. `generateBaselineProfile` and the benchmark run need an emulator and are not run here.
gw "$ROOT/sdk" build -x :sdk-android:dependencyGuard :sdk-android:assembleDebugAndroidTest \
  :sdk-android:signaldockMavenZip :measure-app:assembleRelease :demo:app:assembleRelease \
  :sdk-android:apiCheck :sdk-android:dependencyGuardBaseline

echo "=== server ==="
gw "$ROOT/server" build verifyJooq

echo "=== contract-check ==="
gw "$ROOT/contract-check" build

echo "=== compatibility/consumer-android ==="
VERSION="$(sed -n 's/^val sdkVersion = "\(.*\)"$/\1/p' "$ROOT/sdk/sdk-android/build.gradle.kts")"
[ -n "$VERSION" ] || { echo "error: no sdkVersion in sdk/sdk-android/build.gradle.kts" >&2; exit 1; }
REPO="$(mktemp -d)/maven-repo"
mkdir -p "$REPO"
unzip -q "$ROOT/sdk/sdk-android/build/distributions/signaldock-sdk-android-$VERSION-maven.zip" -d "$REPO"
C="$ROOT/compatibility/consumer-android"
for flag in true false; do
  gw "$C" -Psignaldock.repo="$REPO" -Psignaldock.version="$VERSION" -Psignaldock.sdk=$flag :app:assembleRelease
done
echo "=== aapt2 for the other OS ==="
# Gradle writes only the aapt2 jar of the OS it runs on (osx here, linux on the CI runner). Add the missing
# one, so the file works on both. The checksum is computed from the file on Google Maven (HTTPS).
python3 -I - "$ROOT/sdk/gradle/verification-metadata.xml" "$C/gradle/verification-metadata.xml" <<'EOF'
import hashlib, re, sys, urllib.request

BASE = "https://dl.google.com/dl/android/maven2/com/android/tools/build/aapt2"
for path in sys.argv[1:]:
    text = open(path).read()
    for ver in sorted(set(re.findall(r'<component group="com.android.tools.build" name="aapt2" version="([^"]+)">', text))):
        for os_name in ("linux", "osx"):
            name = f"aapt2-{ver}-{os_name}.jar"
            if f'<artifact name="{name}">' in text:
                continue
            data = urllib.request.urlopen(f"{BASE}/{ver}/{name}").read()
            sha = hashlib.sha256(data).hexdigest()
            entry = (f'         <artifact name="{name}">\n'
                     f'            <sha256 value="{sha}" origin="Added from Google Maven by scripts/update-gradle-verification.sh"/>\n'
                     f'         </artifact>\n')
            marker = f'         <artifact name="aapt2-{ver}.pom">'
            text = text.replace(marker, entry + marker, 1)
            print(f"{path}: added {name}")
    open(path, "w").write(text)
EOF
echo "done: review 'git diff --stat' and commit the four verification-metadata.xml files and any change in sdk/sdk-android/dependencies/"
