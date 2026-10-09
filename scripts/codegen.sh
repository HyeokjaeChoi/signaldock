#!/bin/bash
# Regenerate OpenAPI models from the contract source.
# Pinned tool: openapi-generator-cli 7.26.0 (see gradle/libs.versions.toml).
# Generated output is tracked in Git (Q91); normal compilation must NOT
# overwrite it. CI regenerates into a temp dir and diffs.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="$ROOT/.tools"
GEN_VERSION="7.26.0"
JAR="$TOOLS/openapi-generator-cli-${GEN_VERSION}.jar"
SPEC="$ROOT/openapi/contract-v1.yaml"

if [ ! -f "$JAR" ]; then
  echo "missing $JAR; download openapi-generator-cli ${GEN_VERSION} into .tools/" >&2
  exit 1
fi

# Use the pinned JDK when JAVA_HOME is set; otherwise fall back to PATH java.
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"

OUT="${1:-$ROOT/generated}"
KOTLIN_OUT="$OUT/kotlin"
TS_OUT="$OUT/typescript"

rm -rf "$KOTLIN_OUT" "$TS_OUT"

# Pinned template override lives in openapi/templates (Q91): it fixes the
# oneOf number branch for kotlinx_serialization (upstream maps type:number to
# BigDecimal, which the stock template treats as non-primitive and therefore
# never tries for JSON numbers).
TEMPLATES="$ROOT/openapi/templates"

# Kotlin models for the Android SDK and the Ktor server.
# kotlinx_serialization per ADR-0002; dateLibrary=string keeps occurredAt as a
# String so RFC3339 UTC/ms validation lives in a small adapter (Q81-83),
# since OpenAPI date-time alone does not enforce those constraints.
"$JAVA_BIN" -jar "$JAR" generate \
  -g kotlin \
  -i "$SPEC" \
  -o "$KOTLIN_OUT" \
  -t "$TEMPLATES" \
  --additional-properties=dateLibrary=string,serializationLibrary=kotlinx_serialization,enumPropertyNaming=UPPERCASE,generateOneOfAnyOfWrappers=true \
  --global-property models,modelDocs=false,modelTests=false,apis=false,apiDocs=false,apiTests=false,supportingFiles=

# TypeScript models for the React dashboard (typescript-fetch per ADR-0002).
"$JAVA_BIN" -jar "$JAR" generate \
  -g typescript-fetch \
  -i "$SPEC" \
  -o "$TS_OUT" \
  --global-property models,modelDocs=false,modelTests=false,apis=false,apiDocs=false,apiTests=false,supportingFiles=

echo "generated -> $KOTLIN_OUT"
echo "generated -> $TS_OUT"

# Keep only the models; infrastructure/supporting files are not used
# (models compile standalone). Metadata is kept for drift checks (Q91).
rm -rf "$KOTLIN_OUT/src/main/kotlin/org/openapitools/client/infrastructure"
rm -f "$KOTLIN_OUT/README.md" "$KOTLIN_OUT/build.gradle" "$KOTLIN_OUT/proguard-rules.pro" "$KOTLIN_OUT/.openapi-generator-ignore"
rm -f "$KOTLIN_OUT/settings.gradle" "$KOTLIN_OUT/gradlew" "$KOTLIN_OUT/gradlew.bat"
rm -rf "$KOTLIN_OUT/gradle"
rm -rf "$TS_OUT/.openapi-generator-ignore"
find "$TS_OUT" -maxdepth 1 -name "*.md" -delete
find "$TS_OUT" -maxdepth 1 -name "*.json" -not -name "package.json" -delete

# Rebuild the FILES metadata to match the actual remaining files: the
# generator's list goes stale after the cleanup above, which would make
# drift checks compare against a phantom file set.
for out in "$KOTLIN_OUT" "$TS_OUT"; do
  (cd "$out" && find . -type f ! -path "./.openapi-generator/FILES" ! -path "./.openapi-generator/VERSION" | sed 's|^\./||' | sort > .openapi-generator/FILES)
done
