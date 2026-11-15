#!/bin/bash
# Test of scripts/check-api-semver.sh on a temporary repository with a release tag (ADR-0049 A7, I3).
set -euo pipefail
SCRIPT="$(cd "$(dirname "$0")" && pwd)/check-api-semver.sh"
T="$(mktemp -d)"
trap 'rm -rf "$T"' EXIT
cd "$T"
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
export SEMVER_ROOT="$T"
fail=0
expect() { # expect <0|1> <name> <args>
  want="$1" name="$2"; shift 2
  rc=0; "$SCRIPT" "$@" > /dev/null 2>&1 || rc=$?
  if [ "$rc" != "$want" ]; then echo "FAIL: $name (exit $rc, want $want)"; fail=1; else echo "ok: $name"; fi
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
exit "$fail"
