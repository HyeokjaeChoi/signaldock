#!/bin/bash
# Issue 32: release step. Builds the Maven repository ZIP through scripts/verify-consumer.sh
# (ADR-0009), copies it to dist/ with a versioned name, and writes a SHA-256 file that
# `shasum -a 256 -c` accepts. It does not create a tag, push, or call `gh release`.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="${1:-}"
if [ -z "$VERSION" ]; then
  echo "usage: scripts/release.sh <version>   (for example 0.1.0)" >&2
  exit 1
fi

SDK_VERSION="$(sed -n 's/^val sdkVersion = "\(.*\)"$/\1/p' "$ROOT/sdk/sdk-android/build.gradle.kts")"
if [ "$VERSION" != "$SDK_VERSION" ]; then
  echo "error: version '$VERSION' does not match sdkVersion '$SDK_VERSION' in sdk/sdk-android/build.gradle.kts" >&2
  exit 1
fi

"$ROOT/scripts/verify-consumer.sh"

NAME="signaldock-sdk-android-$VERSION-maven.zip"
SRC="$ROOT/sdk/sdk-android/build/distributions/$NAME"
[ -f "$SRC" ] || { echo "error: $SRC not found" >&2; exit 1; }

DIST="$ROOT/dist"
mkdir -p "$DIST"
cp "$SRC" "$DIST/$NAME"
# Relative file name in the checksum file, so `shasum -a 256 -c` works from dist/.
(cd "$DIST" && shasum -a 256 "$NAME" > "$NAME.sha256" && shasum -a 256 -c "$NAME.sha256")

echo "ZIP:    $DIST/$NAME"
echo "SHA256: $DIST/$NAME.sha256"
cat "$DIST/$NAME.sha256"
