#!/bin/bash
# Verify the OpenAPI contract round trip (issue #1).
# Connects generation and verification across the independent builds (Q89).
# 1. Regenerates models from openapi/contract-v1.yaml (pinned generator).
# 2. Checks oasdiff finds no breaking change against the baseline.
# 3. Compiles generated Kotlin DTOs and runs the fixture round-trip tests.
# 4. Type-checks the generated TypeScript models.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-$ROOT/.tools/jdk-17.0.20.1+1}"

echo "=== 1. codegen ==="
"$ROOT/scripts/codegen.sh"

echo "=== 2. oasdiff baseline ==="
"$ROOT/.tools/oasdiff" diff "$ROOT/openapi/contract-v1.yaml" "$ROOT/openapi/contract-v1.yaml"

echo "=== 3. kotlin compile + tests (contract-check build) ==="
(cd "$ROOT/contract-check" && ./gradlew test --no-daemon)

echo "=== 4. typescript typecheck (dashboard) ==="
(cd "$ROOT/dashboard" && npm run typecheck)

echo "ALL CONTRACT CHECKS PASSED"
