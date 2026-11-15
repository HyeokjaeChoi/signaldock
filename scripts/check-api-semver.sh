#!/bin/bash
# Semver check of a public API dump (ADR-0049 A7, I3; docs/versioning.md).
# Usage: scripts/check-api-semver.sh android|ios
# Fails when the dump has a removed or changed line against the last release tag and the version did not break:
# MAJOR must change, and before 1.0 the MINOR must change. A pure addition always passes.
# android: sdk/sdk-android/api/sdk-android.api against the last `v*` tag; version = sdkVersion.
# ios: sdk-ios/api/SignalDock.swiftinterface against the last `ios-v*` tag; version = SignalDockSDK.version.
# The tag comes from `git ls-remote` and is fetched at depth 1 when missing (CI checkout has no tags). With no remote
# (offline) it uses the local tags. SEMVER_ROOT overrides the repository (used by check-api-semver.test.sh).
set -euo pipefail

ROOT="${SEMVER_ROOT:-$(cd "$(dirname "$0")/.." && pwd)}"
case "${1:-}" in
  android)
    PREFIX="v"
    DUMP="sdk/sdk-android/api/sdk-android.api"
    VERSION_FILE="sdk/sdk-android/build.gradle.kts"
    VERSION_SED='s/^val sdkVersion = "\([0-9][0-9.]*\)"$/\1/p'
    ;;
  ios)
    PREFIX="ios-v"
    DUMP="sdk-ios/api/SignalDock.swiftinterface"
    VERSION_FILE="sdk-ios/Sources/SignalDock/SignalDockSDK.swift"
    VERSION_SED='s/^ *public static let version = "\([0-9][0-9.]*\)"$/\1/p'
    ;;
  *)
    echo "usage: $0 android|ios" >&2
    exit 2
    ;;
esac
cd "$ROOT"

cur="$(sed -n "$VERSION_SED" "$VERSION_FILE" | head -1)"
[ -n "$cur" ] || { echo "error: no version found in $VERSION_FILE" >&2; exit 1; }

tags="$(git ls-remote --tags --refs origin "refs/tags/$PREFIX[0-9]*" 2> /dev/null | sed 's|.*refs/tags/||' || true)"
[ -n "$tags" ] || tags="$(git tag -l "$PREFIX[0-9]*")"
tag="$(printf '%s\n' "$tags" | grep -E "^$PREFIX[0-9]+\.[0-9]+\.[0-9]+$" | sort -V | tail -1 || true)"
[ -n "$tag" ] || { echo "error: no '$PREFIX*' release tag found (remote or local)" >&2; exit 1; }
if ! git rev-parse -q --verify "refs/tags/$tag^{commit}" > /dev/null; then
  git fetch -q --depth=1 --no-tags origin "+refs/tags/$tag:refs/tags/$tag"
fi

old="${tag#"$PREFIX"}"
IFS=. read -r old_major old_minor _ <<< "$old"
IFS=. read -r cur_major cur_minor _ <<< "$cur"

old_dump="$(mktemp)"
trap 'rm -f "$old_dump"' EXIT
git show "$tag:$DUMP" > "$old_dump"

# Lines that exist only in the old dump: removed, or the old half of a changed declaration.
removed="$(diff "$old_dump" "$DUMP" | sed -n 's/^< //p' || true)"
if [ -z "$removed" ]; then
  echo "ok: $DUMP has no removed or changed line against $tag (version $cur)"
  exit 0
fi
if [ "$cur_major" != "$old_major" ] || { [ "$old_major" = 0 ] && [ "$cur_minor" != "$old_minor" ]; }; then
  echo "ok: $DUMP has removed or changed lines against $tag, and version $cur is a breaking version from $old"
  exit 0
fi
{
  echo "error: $DUMP has removed or changed lines against $tag, but the version is still $cur (last release $old)."
  echo "Raise MAJOR (before 1.0: MINOR) in $VERSION_FILE, or restore the API (docs/versioning.md). Removed or changed:"
  printf '%s\n' "$removed" | head -20
} >&2
exit 1
