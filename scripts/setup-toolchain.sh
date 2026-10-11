#!/bin/bash
# Install the pinned toolchain into .tools/ (run once per clone).
# Versions: see TOOLCHAIN.md and gradle/libs.versions.toml.
# Every download is checked against a hardcoded hash from the publisher.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="$ROOT/.tools"
JDK_DIR="$TOOLS/jdk-17.0.20.1+1"
OASDIFF="$TOOLS/oasdiff"
GEN_JAR="$TOOLS/openapi-generator-cli-7.26.0.jar"

OS="$(uname -s)"
ARCH="$(uname -m)"
case "$OS-$ARCH" in
  Darwin-arm64)
    JDK_OS=mac; JDK_ARCH=aarch64
    JDK_SHA=196d13ba5f10414bef7f6a05a9b3f00edacb18ebacef2b99485db9e2ee18f0e8
    OAS_FILE=oasdiff_1.33.0_darwin_all.tar.gz
    OAS_SHA=2a479337c15afdcbf0b1e89c0b4d0cf0176472dbc11483358fed1f831d1e46c5 ;;
  Darwin-x86_64)
    JDK_OS=mac; JDK_ARCH=x64
    JDK_SHA=c01975da12ed4235250ff891fe8bba73a9e73037d444b269c9d0922b5dbc8e0a
    OAS_FILE=oasdiff_1.33.0_darwin_all.tar.gz
    OAS_SHA=2a479337c15afdcbf0b1e89c0b4d0cf0176472dbc11483358fed1f831d1e46c5 ;;
  Linux-x86_64)
    JDK_OS=linux; JDK_ARCH=x64
    JDK_SHA=3808d1d15e3ec6bd5b84057fb5d84c33d8a1536a258146bcea2e603fc726e08e
    OAS_FILE=oasdiff_1.33.0_linux_amd64.tar.gz
    OAS_SHA=43a4e328e2d13ba1552d760aa68d2485c75c5621f309f6ff64ae895188345247 ;;
  Linux-aarch64|Linux-arm64)
    JDK_OS=linux; JDK_ARCH=aarch64
    JDK_SHA=457b57af8f9c93ec39080bb8c764f559dc8c89a6da1a39d718a400b7890d3e41
    OAS_FILE=oasdiff_1.33.0_linux_arm64.tar.gz
    OAS_SHA=4ae3c362d6074d919aada2dea82d0ee84366591600384455d0bdc658ddf8f7ae ;;
  *) echo "error: unsupported platform $OS-$ARCH (need darwin/linux, arm64/x64)" >&2; exit 1 ;;
esac

# JDK SHA-256: Adoptium API
#   https://api.adoptium.net/v3/assets/release_name/eclipse/jdk-17.0.20.1%2B1?image_type=jdk&os=<os>&architecture=<arch>&project=jdk
# oasdiff SHA-256: https://github.com/oasdiff/oasdiff/releases/download/v1.33.0/checksums.txt
# openapi-generator-cli: Maven Central publishes no SHA-256 (only SHA-1/MD5).
#   SHA-1 from https://repo1.maven.org/maven2/org/openapitools/openapi-generator-cli/7.26.0/openapi-generator-cli-7.26.0.jar.sha1
#   The SHA-256 was recorded after the SHA-1 matched (trust on first use).
JDK_URL="https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_${JDK_ARCH}_${JDK_OS}_hotspot_17.0.20.1_1.tar.gz"
OAS_URL="https://github.com/oasdiff/oasdiff/releases/download/v1.33.0/$OAS_FILE"
GEN_URL="https://repo1.maven.org/maven2/org/openapitools/openapi-generator-cli/7.26.0/openapi-generator-cli-7.26.0.jar"
GEN_SHA256=1760050094997b9cc790cc1350be095f15da8c08a996b184d3eadc4ccda5e35e
GEN_SHA1=b96b148fcf482808037f68fbad99cb4d7e65d40b

sha() { # file algo -> hex
  if command -v shasum >/dev/null; then shasum -a "$2" "$1" | cut -d' ' -f1
  else "sha${2}sum" "$1" | cut -d' ' -f1; fi
}
check() { # file algo expected
  local got; got="$(sha "$1" "$2")"
  [ "$got" = "$3" ] || { echo "error: SHA-$2 mismatch for $(basename "$1"): expected $3, got $got" >&2; exit 1; }
}

mkdir -p "$TOOLS"
TMP="$(mktemp -d "$TOOLS/.tmp.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

if [ -x "$JDK_DIR/bin/java" ]; then
  echo "skip: JDK already at $JDK_DIR"
else
  curl -fsSL -o "$TMP/jdk.tar.gz" "$JDK_URL"
  check "$TMP/jdk.tar.gz" 256 "$JDK_SHA"
  mkdir "$TMP/jdk"
  tar -xzf "$TMP/jdk.tar.gz" -C "$TMP/jdk"
  SRC="$(echo "$TMP"/jdk/*)"
  if [ "$JDK_OS" = mac ]; then SRC="$SRC/Contents/Home"; fi
  [ -x "$SRC/bin/java" ] || { echo "error: no bin/java in extracted JDK" >&2; exit 1; }
  mv "$SRC" "$JDK_DIR"
  echo "installed: JDK Temurin 17.0.20.1+1 -> $JDK_DIR"
fi

if [ -x "$OASDIFF" ] && "$OASDIFF" --version 2>/dev/null | grep -q '1\.33\.0'; then
  echo "skip: oasdiff 1.33.0 already at $OASDIFF"
else
  curl -fsSL -o "$TMP/oasdiff.tar.gz" "$OAS_URL"
  check "$TMP/oasdiff.tar.gz" 256 "$OAS_SHA"
  mkdir "$TMP/oas"
  tar -xzf "$TMP/oasdiff.tar.gz" -C "$TMP/oas" oasdiff
  mv "$TMP/oas/oasdiff" "$OASDIFF"
  chmod +x "$OASDIFF"
  echo "installed: oasdiff v1.33.0 -> $OASDIFF"
fi

if [ -f "$GEN_JAR" ] && [ "$(sha "$GEN_JAR" 256)" = "$GEN_SHA256" ]; then
  echo "skip: openapi-generator-cli 7.26.0 already at $GEN_JAR"
else
  curl -fsSL -o "$TMP/gen.jar" "$GEN_URL"
  check "$TMP/gen.jar" 1 "$GEN_SHA1"
  check "$TMP/gen.jar" 256 "$GEN_SHA256"
  mv "$TMP/gen.jar" "$GEN_JAR"
  echo "installed: openapi-generator-cli 7.26.0 -> $GEN_JAR"
fi
