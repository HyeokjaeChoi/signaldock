#!/bin/bash
# Verify the OpenAPI contract round trip (issue #1).
# Connects generation and verification across the independent builds (Q89).
# 1. Regenerates models into a temp dir and diffs against tracked generated/
#    (verification never rewrites the checkout).
# 2. Checks oasdiff finds no unrecorded change against the v1 baseline.
# 3. Compiles generated Kotlin DTOs and runs the fixture round-trip tests.
# 4. Installs pinned dashboard deps (npm ci from the committed lockfile),
#    type-checks the dashboard, and runs the executable TS round-trip assertions.
# 5. Evaluates the independent sdk/ and server/ Gradle builds.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# Pinned JDK (see TOOLCHAIN.md): use the bundled copy only when it exists;
# otherwise require JAVA_HOME to be set already.
if [ -z "${JAVA_HOME:-}" ]; then
  if [ -x "$ROOT/.tools/jdk-17.0.20.1+1/bin/java" ]; then
    export JAVA_HOME="$ROOT/.tools/jdk-17.0.20.1+1"
  else
    echo "error: JAVA_HOME is not set and no bundled JDK at $ROOT/.tools/jdk-17.0.20.1+1" >&2
    echo "Download the pinned JDK (see TOOLCHAIN.md) or set JAVA_HOME." >&2
    exit 1
  fi
fi

echo "=== 1. codegen drift check ==="
TMP_GEN="$(mktemp -d)"
trap 'rm -rf "$TMP_GEN"' EXIT
"$ROOT/scripts/codegen.sh" "$TMP_GEN" > /dev/null
if ! diff -rq "$ROOT/generated" "$TMP_GEN"; then
  echo "error: generated/ drifted from openapi/contract-v1.yaml" >&2
  echo "run scripts/codegen.sh and commit the result" >&2
  exit 1
fi

echo "=== 2. oasdiff baseline ==="
# Fails on any change vs the checked-in v1 baseline: when the contract
# changes deliberately, update fixtures/contract/contract-v1.baseline.yaml
# in the same PR.
"$ROOT/.tools/oasdiff" diff --fail-on-diff "$ROOT/fixtures/contract/contract-v1.baseline.yaml" "$ROOT/openapi/contract-v1.yaml"

echo "=== 3. kotlin compile + tests (contract-check build) ==="
(cd "$ROOT/contract-check" && ./gradlew test --no-daemon)

echo "=== 4. typescript contract check (dashboard) ==="
(cd "$ROOT/dashboard" && npm ci && npm run typecheck && npm run contract-check)

echo "=== 5. independent sdk/ and server/ builds ==="
(cd "$ROOT/sdk" && ./gradlew help --no-daemon -q)
(cd "$ROOT/server" && ./gradlew help --no-daemon -q)

echo "ALL CONTRACT CHECKS PASSED"
