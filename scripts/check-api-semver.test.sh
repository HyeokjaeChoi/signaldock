#!/bin/bash
# Test of scripts/check-api-semver.sh on a temporary repository with a release tag (ADR-0049 A7, I3).
set -euo pipefail
# A git hook exports these; without this, the git calls below would change the real repository.
unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE GIT_PREFIX
SCRIPT="$(cd "$(dirname "$0")" && pwd)/check-api-semver.sh"
T="$(mktemp -d)"
trap 'rm -rf "$T"' EXIT
mkdir "$T/w"
cd "$T/w"
git init -q .
git config user.email t@t && git config user.name t
mkdir -p sdk/sdk-android/api sdk-ios/api sdk-ios/Sources/SignalDock
setv() { # setv <android version> <ios version>
  printf 'val sdkVersion = "%s"\n' "$1" > sdk/sdk-android/build.gradle.kts
  printf 'public final class SignalDockSDK {\n  public static let version = "%s"\n}\n' "$2" > sdk-ios/Sources/SignalDock/SignalDockSDK.swift
}
dump() { printf 'class A {\n\tfun a ()V\n\tfun b ()V\n}\n%s' "${1:-}" | tee sdk/sdk-android/api/sdk-android.api > sdk-ios/api/SignalDock.swiftinterface; }
setv 0.1.0 0.1.0
dump
git add -A && git commit -qm base && git tag v0.1.0 && git tag ios-v0.1.0
git branch -M main
git init -q --bare "$T/canon.git"
git remote add origin "$T/canon.git"
push_canon() { git push -q origin main --tags; }
push_canon
export SEMVER_ROOT="$T/w"
export SEMVER_CANONICAL="$T/canon.git" # the default is the GitHub URL; only this test overrides it
fail=0
EXTRA_ENV="" # `env` arguments for the next expect
expect() { # expect <0|1> <name> <args>; stdout in $T/out, stderr in $T/err
  local want="$1" name="$2" rc=0; shift 2
  # shellcheck disable=SC2086 # word splitting of EXTRA_ENV is intended
  env $EXTRA_ENV "$SCRIPT" "$@" > "$T/out" 2> "$T/err" || rc=$?
  if [ "$rc" != "$want" ]; then echo "FAIL: $name (exit $rc, want $want)"; fail=1; else echo "ok: $name"; fi
}
has() { # has <out|err> <text> <name>
  if grep -qF -- "$2" "$T/$1"; then echo "ok: $3"; else echo "FAIL: $3 ($1 lacks '$2')"; fail=1; fi
}
hasnot() { # hasnot <out|err> <text> <name>
  if grep -qF -- "$2" "$T/$1"; then echo "FAIL: $3 ($1 has '$2')"; fail=1; else echo "ok: $3"; fi
}
for p in android ios; do
  expect 0 "$p: unchanged" "$p"
  dump 'fun c ()V
'
  expect 0 "$p: addition only" "$p"
  printf 'class A {\n\tfun a ()V\n}\n' | tee sdk/sdk-android/api/sdk-android.api > sdk-ios/api/SignalDock.swiftinterface
  expect 1 "$p: removed line, same version" "$p"
  setv 0.1.1 0.1.1
  expect 1 "$p: removed line, patch bump" "$p"
  setv 0.2.0 0.2.0
  expect 0 "$p: removed line, minor bump before 1.0" "$p"
  setv 0.1.0 0.1.0
  printf 'class A {\n\tfun a ()V\n\tfun b ()I\n}\n' | tee sdk/sdk-android/api/sdk-android.api > sdk-ios/api/SignalDock.swiftinterface
  expect 1 "$p: changed line, same version" "$p"
  dump
done
# From 1.0 on, MINOR does not count: only MAJOR does.
setv 1.0.0 1.0.0
dump
git add -A && git commit -qm one && git tag v1.0.0 && git tag ios-v1.0.0
push_canon
printf 'class A {\n\tfun a ()V\n}\n' | tee sdk/sdk-android/api/sdk-android.api > sdk-ios/api/SignalDock.swiftinterface
setv 1.1.0 1.1.0
expect 1 "android: removed line, minor bump after 1.0" android
expect 1 "ios: removed line, minor bump after 1.0" ios
setv 2.0.0 2.0.0
expect 0 "android: removed line, major bump" android
expect 0 "ios: removed line, major bump" ios

# A newer release on main; later the backport branch must not compare with it.
setv 1.1.0 1.1.0
dump 'fun z ()V
'
git add -A && git commit -qm two && git tag v1.1.0 && git tag ios-v1.1.0
git commit -q --allow-empty -m tip # a tip with no tag of its own
push_canon

# Canonical remote.
expect 0 "canonical origin" android
git remote set-url origin https://github.com/someone/signaldock.git
EXTRA_ENV="-u SEMVER_CANONICAL"
expect 1 "fork origin, default canonical" android
has err SEMVER_REMOTE "fork origin: error names SEMVER_REMOTE"
EXTRA_ENV=""
git remote add upstream "$T/canon.git"
EXTRA_ENV="SEMVER_REMOTE=upstream"
expect 0 "fork origin, SEMVER_REMOTE=upstream" android
EXTRA_ENV=""

# URL normalization: an ssh URL of the canonical repository is accepted (the listing then fails offline).
git remote set-url origin git@github.com:HyeokjaeChoi/signaldock.git
EXTRA_ENV="-u SEMVER_CANONICAL GIT_SSH_COMMAND=false"
expect 0 "ssh URL of the canonical repository" android
hasnot err "not the canonical" "ssh URL: no canonical error"
has err "warning: cannot list tags" "ssh URL: ls-remote warning"
EXTRA_ENV=""

# ls-remote failure: warning, then local tags.
git remote set-url origin "$T/missing.git"
SEMVER_CANONICAL="$T/missing.git" expect 0 "ls-remote failure uses local tags" android
has err "warning: cannot list tags" "ls-remote failure: warning"
has out v1.1.0 "ls-remote failure: local tag used"
git remote set-url origin "$T/canon.git"

# Shallow clone (the CI checkout): the tag and the history are fetched.
git clone -q --depth 1 --branch main "file://$T/canon.git" "$T/shallow"
for p in android ios; do
  SEMVER_ROOT="$T/shallow" SEMVER_CANONICAL="file://$T/canon.git" expect 0 "$p: shallow clone" "$p"
done
has out v1.1.0 "shallow clone: nearest tag used"

# Nearest ancestor: a backport branch from v0.1.0 compares with v0.1.0, not with the newer v1.1.0 (which has an added line).
git checkout -q -f -b backport v0.1.0
setv 0.1.1 0.1.1
for p in android ios; do
  expect 0 "$p: backport branch" "$p"
done
has out v0.1.0 "backport branch: v0.1.0 used"
exit "$fail"
