#!/bin/bash
# Verify the OpenAPI contract round trip (issue #1).
# Connects generation and verification across the independent builds (Q89).
# 1. Regenerates models into a temp dir and diffs against tracked generated/
#    (verification never rewrites the checkout).
# 2. Checks oasdiff finds no unrecorded change against the v1 baseline.
# 3. Compiles generated Kotlin DTOs and runs the fixture round-trip tests.
# 4. Installs pinned dashboard deps (npm ci from the committed lockfile),
#    runs the dashboard tests and the bundle-has-no-query-key check (Q129),
#    type-checks the dashboard, and runs the executable TS round-trip assertions.
# 5. Evaluates the independent sdk/ and server/ Gradle builds.
# 6. Checks the SDK public API dump (ADR-0019) and the published dependency baseline (ADR-0033).
# 7. Tracking plan (ADR-0021): regenerates into a temp dir and diffs against
#    tracking-plan/generated/, then runs the generator and cross-language tests.
# 8. iOS SDK (ADR-0047 D13; format lint, TSan, ASan, analyze, privacy manifest, public API diff against
#    sdk-ios/api/SignalDock.swiftinterface): runs only with full Xcode and an iOS Simulator runtime (scripts/verify-ios.sh).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# Pinned JDK (see TOOLCHAIN.md): use the bundled copy only when it exists;
# otherwise require JAVA_HOME to be set already.
if [ -z "${JAVA_HOME:-}" ]; then
  if [ -x "$ROOT/.tools/jdk-21.0.12.1+1/bin/java" ]; then
    export JAVA_HOME="$ROOT/.tools/jdk-21.0.12.1+1"
  else
    echo "error: JAVA_HOME is not set and no bundled JDK at $ROOT/.tools/jdk-21.0.12.1+1" >&2
    echo "Download the pinned JDK (see TOOLCHAIN.md) or set JAVA_HOME." >&2
    exit 1
  fi
fi

echo "=== 1. codegen drift check ==="
TMP_GEN="$(mktemp -d)"
TMP_PLAN="$TMP_GEN-plan" # created in step 7
trap 'rm -rf "$TMP_GEN" "$TMP_PLAN"' EXIT # the only trap: later steps must not reset it
"$ROOT/scripts/codegen.sh" "$TMP_GEN" > /dev/null
if ! diff -rq -x swift "$ROOT/generated" "$TMP_GEN"; then # swift is checked below
  echo "error: generated/ drifted from openapi/contract-v1.yaml" >&2
  echo "run scripts/codegen.sh and commit the result" >&2
  exit 1
fi
if ! diff -rq "$ROOT/sdk-ios/Sources/SignalDock/Generated" "$TMP_GEN/swift"; then
  echo "error: sdk-ios/Sources/SignalDock/Generated drifted from openapi/contract-v1.yaml" >&2
  echo "run scripts/codegen.sh and commit the result" >&2
  exit 1
fi

echo "=== 2. oasdiff baseline ==="
# Fails on any change vs the checked-in v1 baseline: when the contract
# changes deliberately, update fixtures/contract/contract-v1.baseline.yaml
# in the same PR.
if [ ! -x "$ROOT/.tools/oasdiff" ]; then
  echo "error: $ROOT/.tools/oasdiff is missing or not executable" >&2
  echo "Run scripts/setup-toolchain.sh (see TOOLCHAIN.md)." >&2
  exit 1
fi
"$ROOT/.tools/oasdiff" diff --fail-on-diff "$ROOT/fixtures/contract/contract-v1.baseline.yaml" "$ROOT/openapi/contract-v1.yaml"

echo "=== 3. kotlin compile + tests (contract-check build) ==="
(cd "$ROOT/contract-check" && ./gradlew test --no-daemon)

echo "=== 4. typescript contract check (dashboard) ==="
(cd "$ROOT/dashboard" && npm ci && npm run typecheck && npm test && npm run contract-check && npm run check-bundle-key)

echo "=== 5. independent sdk/ and server/ builds ==="
(cd "$ROOT/sdk" && ./gradlew help --no-daemon -q)
(cd "$ROOT/server" && ./gradlew help --no-daemon -q)

echo "=== 6. SDK public API dump (ADR-0019) and dependency baseline (ADR-0033) ==="
# Fails when the public API of :sdk-android differs from sdk/sdk-android/api/sdk-android.api.
# Needs the Android SDK (it compiles the release variant).
if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ] && ! grep -q '^sdk.dir=' "$ROOT/sdk/local.properties" 2>/dev/null; then
  echo "error: step 6 needs the Android SDK; set ANDROID_HOME or sdk.dir in sdk/local.properties" >&2
  exit 1
fi
(cd "$ROOT/sdk" && ./gradlew :sdk-android:apiCheck --no-daemon -q)
# Dependency baseline (ADR-0033): fails when the release runtime or compile classpath of :sdk-android
# differs from sdk/sdk-android/dependencies/. Update: ./gradlew :sdk-android:dependencyGuardBaseline
(cd "$ROOT/sdk" && ./gradlew :sdk-android:dependencyGuard --no-daemon -q)

echo "=== 7. tracking plan codegen drift + cross-language test (ADR-0021) ==="
# Needs only Node (no Android SDK). Verification never rewrites the checkout.
mkdir -p "$TMP_PLAN"
node "$ROOT/tracking-plan/generate.mjs" "$TMP_PLAN" > /dev/null
if ! diff -rq "$ROOT/tracking-plan/generated" "$TMP_PLAN"; then
  echo "error: tracking-plan/generated/ drifted from tracking-plan/plan.json" >&2
  echo "run node tracking-plan/generate.mjs and commit the result" >&2
  exit 1
fi
node --test "$ROOT/tracking-plan/generate.test.mjs"

echo "=== 8. iOS SDK: format lint, Simulator tests under TSan and ASan, analyze, manifest, public API (ADR-0047 D13) ==="
# CI runs these on macOS in the ios-test and ios-sanitize jobs. A Mac with only Command Line Tools has an
# xcodebuild shim, so the check needs full Xcode (`xcodebuild -version`) and an iOS Simulator runtime.
if "$ROOT/scripts/verify-ios.sh" available; then
  "$ROOT/scripts/verify-ios.sh" all
else
  echo "skip: no full Xcode with an iOS Simulator runtime; the iOS steps run in CI (ios-test, ios-sanitize)"
fi

echo "ALL CONTRACT CHECKS PASSED"
