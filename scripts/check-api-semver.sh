#!/bin/bash
# Semver check of a public API dump (ADR-0049 A7, I3; docs/versioning.md).
# Usage: scripts/check-api-semver.sh android|ios
# Fails when the dump has a removed or changed line against the last release tag and the version did not break:
# MAJOR must change, and before 1.0 the MINOR must change. A pure addition always passes.
# android: sdk/sdk-android/api/sdk-android.api against the nearest `v*` tag; version = sdkVersion.
# ios: sdk-ios/api/SignalDock.swiftinterface against the nearest `ios-v*` tag; version = SignalDockSDK.version.
# "Nearest" = the highest `X.Y.Z` release tag whose commit is an ancestor of HEAD (a backport branch compares with its own line).
# Remote: SEMVER_REMOTE (default origin) must be the canonical repository, else the check fails (a fork can have old tags).
# SEMVER_CANONICAL overrides the canonical URL; only check-api-semver.test.sh sets it.
# Tags come from `git ls-remote`; a missing tag is fetched (CI checkout has depth 1 and no tags, so the history is
# fetched first, because the ancestor test needs it). If `git ls-remote` fails, the script warns and uses the local tags.
# SEMVER_ROOT overrides the repository (used by check-api-semver.test.sh).
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

REMOTE="${SEMVER_REMOTE:-origin}"
CANONICAL="${SEMVER_CANONICAL:-https://github.com/HyeokjaeChoi/signaldock}"
norm() { # lowercase, no scheme or user, `host:path` as `host/path`, no trailing `/` or `.git`
  printf '%s' "$1" | tr '[:upper:]' '[:lower:]' | sed -e 's|^https://||;s|^http://||;s|^ssh://git@||;s|^git://||;s|^git@||' \
    -e 's|github\.com:|github.com/|' -e 's|/*$||' -e 's|\.git$||'
}
url="$(git remote get-url "$REMOTE" 2> /dev/null || true)"
if [ -z "$url" ] || [ "$(norm "$url")" != "$(norm "$CANONICAL")" ]; then
  {
    echo "error: remote '$REMOTE' ('${url:-none}') is not the canonical repository ($CANONICAL)."
    echo "A fork can have old tags, so the check could compare with an old release."
    echo "Set SEMVER_REMOTE to the remote of the canonical repository (for example: SEMVER_REMOTE=upstream)."
  } >&2
  exit 1
fi

if listed="$(git ls-remote --tags --refs "$REMOTE" "refs/tags/${PREFIX}[0-9]*" 2> /dev/null)"; then
  tags="$(printf '%s\n' "$listed" | sed 's|.*refs/tags/||')"
else
  echo "warning: cannot list tags of $REMOTE ($url); using local tags, which can be old" >&2
  tags=""
fi
[ -n "$tags" ] || tags="$(git tag -l "${PREFIX}[0-9]*")"
candidates="$(printf '%s\n' "$tags" | grep -E "^${PREFIX}[0-9]+\.[0-9]+\.[0-9]+$" | sort -rV || true)"
[ -n "$candidates" ] || { echo "error: no '$PREFIX*' release tag found (remote or local)" >&2; exit 1; }
if [ "$(git rev-parse --is-shallow-repository)" = true ]; then # ancestry needs history
  git fetch -q --unshallow --no-tags "$REMOTE" || { echo "error: cannot fetch the history of $REMOTE (needed for the ancestor test)" >&2; exit 1; }
fi
tag=""
while IFS= read -r t; do
  if ! git rev-parse -q --verify "refs/tags/$t^{commit}" > /dev/null; then
    git fetch -q --no-tags "$REMOTE" "+refs/tags/$t:refs/tags/$t" < /dev/null || { echo "error: cannot fetch tag $t from $REMOTE" >&2; exit 1; }
  fi
  if git merge-base --is-ancestor "refs/tags/$t^{commit}" HEAD; then
    tag="$t"
    break
  fi
done <<< "$candidates"
[ -n "$tag" ] || { echo "error: no '$PREFIX*' release tag is an ancestor of HEAD" >&2; exit 1; }

old="${tag#"$PREFIX"}"
IFS=. read -r old_major old_minor _ <<< "$old"
IFS=. read -r cur_major cur_minor _ <<< "$cur"

old_dump="$(mktemp)"
trap 'rm -f "$old_dump"' EXIT
git show "$tag:$DUMP" > "$old_dump"

# Lines that exist only in the old dump: removed, or the old half of a changed declaration.
rc=0
changes="$(diff "$old_dump" "$DUMP")" || rc=$?
[ "$rc" -le 1 ] || { echo "error: cannot compare $DUMP with $tag" >&2; exit 1; }
removed="$(printf '%s\n' "$changes" | sed -n 's/^< //p')"
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
