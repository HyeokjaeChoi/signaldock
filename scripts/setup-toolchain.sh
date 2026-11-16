#!/bin/bash
# Install the pinned toolchain into .tools/ (run once per clone).
# Versions: see TOOLCHAIN.md and gradle/libs.versions.toml.
# Every download is checked against a hardcoded hash from the publisher.
# Usage: setup-toolchain.sh [lint|osv]
#   no argument  install everything
#   lint         install only shellcheck and actionlint (the CI `check` job; no JDK download)
#   osv          install only osv-scanner (the CI `osv-scan` job and the nightly job)
set -euo pipefail

MODE="${1:-all}"
case "$MODE" in all | lint | osv) ;; *) echo "usage: $0 [lint|osv]" >&2; exit 2 ;; esac

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="$ROOT/.tools"
JDK_DIR="$TOOLS/jdk-21.0.12.1+1"
OASDIFF="$TOOLS/oasdiff"
SHELLCHECK="$TOOLS/shellcheck"
ACTIONLINT="$TOOLS/actionlint"
OSV_SCANNER="$TOOLS/osv-scanner"
# The only place of the OSV-Scanner version; scripts/osv-scan.sh reads it from here.
OSV_VERSION=2.7.1
GEN_JAR="$TOOLS/openapi-generator-cli-7.26.0.jar"

OS="$(uname -s)"
ARCH="$(uname -m)"
case "$OS-$ARCH" in
  Darwin-arm64)
    JDK_OS=mac; JDK_ARCH=aarch64
    JDK_SHA=3623232f33a9c3baadf304480b2535f9a3cba8a58d42ecbb438ba267315d9998
    OAS_FILE=oasdiff_1.33.0_darwin_all.tar.gz
    OAS_SHA=2a479337c15afdcbf0b1e89c0b4d0cf0176472dbc11483358fed1f831d1e46c5
    SC_FILE=shellcheck-v0.11.0.darwin.aarch64.tar.gz
    SC_SHA=339b930feb1ea764467013cc1f72d09cd6b869ebf1013296ba9055ab2ffbd26f
    AL_FILE=actionlint_1.7.12_darwin_arm64.tar.gz
    AL_SHA=aba9ced2dee8d27fecca3dc7feb1a7f9a52caefa1eb46f3271ea66b6e0e6953f
    OSV_FILE=osv-scanner_darwin_arm64
    OSV_SHA=b22c02877a811c8f78956e8643efa554932b7378f590d13f8e97d4e9448a29f3 ;;
  Darwin-x86_64)
    JDK_OS=mac; JDK_ARCH=x64
    JDK_SHA=44db0f08196daf19a47f90d13388b0c943b67663cb537f998fe29e836fa842ce
    OAS_FILE=oasdiff_1.33.0_darwin_all.tar.gz
    OAS_SHA=2a479337c15afdcbf0b1e89c0b4d0cf0176472dbc11483358fed1f831d1e46c5
    SC_FILE=shellcheck-v0.11.0.darwin.x86_64.tar.gz
    SC_SHA=c2c15e08df0e8fbc374c335b230a7ee958c313fa5714817a59aa59f1aa594f51
    AL_FILE=actionlint_1.7.12_darwin_amd64.tar.gz
    AL_SHA=5b44c3bc2255115c9b69e30efc0fecdf498fdb63c5d58e17084fd5f16324c644
    OSV_FILE=osv-scanner_darwin_amd64
    OSV_SHA=b8420d5485ba792626315f1bf9a5a2046e971d38eea75bf9090c38852dbc3b1b ;;
  Linux-x86_64)
    JDK_OS=linux; JDK_ARCH=x64
    JDK_SHA=ce79869e1307ed8ee1e2baa86a412b1eb5b75d10a01006d788a6f968bcfaee94
    OAS_FILE=oasdiff_1.33.0_linux_amd64.tar.gz
    OAS_SHA=43a4e328e2d13ba1552d760aa68d2485c75c5621f309f6ff64ae895188345247
    SC_FILE=shellcheck-v0.11.0.linux.x86_64.tar.gz
    SC_SHA=b7af85e41cc99489dcc21d66c6d5f3685138f06d34651e6d34b42ec6d54fe6f6
    AL_FILE=actionlint_1.7.12_linux_amd64.tar.gz
    AL_SHA=8aca8db96f1b94770f1b0d72b6dddcb1ebb8123cb3712530b08cc387b349a3d8
    OSV_FILE=osv-scanner_linux_amd64
    OSV_SHA=fb7dab19a8bd9bbe5c3189f09d4b7ee1820c9553a23bac202648a56efd2f6ee8 ;;
  Linux-aarch64|Linux-arm64)
    JDK_OS=linux; JDK_ARCH=aarch64
    JDK_SHA=23e37e026f12f3e706f18938ff611db3032d075b09d0879a25d06718c773e223
    OAS_FILE=oasdiff_1.33.0_linux_arm64.tar.gz
    OAS_SHA=4ae3c362d6074d919aada2dea82d0ee84366591600384455d0bdc658ddf8f7ae
    SC_FILE=shellcheck-v0.11.0.linux.aarch64.tar.gz
    SC_SHA=68a8133197a50beb8803f8d42f9908d1af1c5540d4bb05fdfca8c1fa47decefc
    AL_FILE=actionlint_1.7.12_linux_arm64.tar.gz
    AL_SHA=325e971b6ba9bfa504672e29be93c24981eeb1c07576d730e9f7c8805afff0c6
    OSV_FILE=osv-scanner_linux_arm64
    OSV_SHA=983d22fbad9724514e3fcc6acc83b5fa64c5ffa6b0d9f510028f22c1b223ff81 ;;
  *) echo "error: unsupported platform $OS-$ARCH (need darwin/linux, arm64/x64)" >&2; exit 1 ;;
esac

# JDK SHA-256: Adoptium API
#   https://api.adoptium.net/v3/assets/release_name/eclipse/jdk-21.0.12.1%2B1?image_type=jdk&os=<os>&architecture=<arch>&project=jdk
# oasdiff SHA-256: https://github.com/oasdiff/oasdiff/releases/download/v1.33.0/checksums.txt
# SHA-256 of shellcheck: the GitHub release asset digest (`gh api repos/koalaman/shellcheck/releases/tags/v0.11.0`);
#   the publisher ships no checksums file. Recorded after downloading each tarball and hashing it.
# SHA-256 of actionlint: https://github.com/rhysd/actionlint/releases/download/v1.7.12/actionlint_1.7.12_checksums.txt
# SHA-256 of osv-scanner: the GitHub release asset digest (`gh api repos/google/osv-scanner/releases/tags/v$OSV_VERSION`);
#   the release also has osv-scanner_SHA256SUMS. The asset is the binary itself, not an archive.
# openapi-generator-cli: Maven Central publishes no SHA-256 (only SHA-1/MD5).
#   SHA-1 from https://repo1.maven.org/maven2/org/openapitools/openapi-generator-cli/7.26.0/openapi-generator-cli-7.26.0.jar.sha1
#   The SHA-256 was recorded after the SHA-1 matched (trust on first use).
JDK_URL="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_${JDK_ARCH}_${JDK_OS}_hotspot_21.0.12.1_1.tar.gz"
OAS_URL="https://github.com/oasdiff/oasdiff/releases/download/v1.33.0/$OAS_FILE"
SC_URL="https://github.com/koalaman/shellcheck/releases/download/v0.11.0/$SC_FILE"
AL_URL="https://github.com/rhysd/actionlint/releases/download/v1.7.12/$AL_FILE"
OSV_URL="https://github.com/google/osv-scanner/releases/download/v$OSV_VERSION/$OSV_FILE"
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

if [ "$MODE" = osv ]; then
  :
elif [ -x "$SHELLCHECK" ] && "$SHELLCHECK" --version 2>/dev/null | grep -q '^version: 0\.11\.0$'; then
  echo "skip: shellcheck 0.11.0 already at $SHELLCHECK"
else
  curl -fsSL -o "$TMP/shellcheck.tar.gz" "$SC_URL"
  check "$TMP/shellcheck.tar.gz" 256 "$SC_SHA"
  mkdir "$TMP/sc"
  tar -xzf "$TMP/shellcheck.tar.gz" -C "$TMP/sc" --strip-components=1 shellcheck-v0.11.0/shellcheck
  mv "$TMP/sc/shellcheck" "$SHELLCHECK"
  chmod +x "$SHELLCHECK"
  echo "installed: shellcheck v0.11.0 -> $SHELLCHECK"
fi

if [ "$MODE" = osv ]; then
  :
elif [ -x "$ACTIONLINT" ] && "$ACTIONLINT" --version 2>/dev/null | head -1 | grep -q '^1\.7\.12$'; then
  echo "skip: actionlint 1.7.12 already at $ACTIONLINT"
else
  curl -fsSL -o "$TMP/actionlint.tar.gz" "$AL_URL"
  check "$TMP/actionlint.tar.gz" 256 "$AL_SHA"
  mkdir "$TMP/al"
  tar -xzf "$TMP/actionlint.tar.gz" -C "$TMP/al" actionlint
  mv "$TMP/al/actionlint" "$ACTIONLINT"
  chmod +x "$ACTIONLINT"
  echo "installed: actionlint v1.7.12 -> $ACTIONLINT"
fi

if [ "$MODE" = lint ]; then exit 0; fi

if [ -x "$OSV_SCANNER" ] && "$OSV_SCANNER" --version 2>/dev/null | head -1 | grep -qxF "osv-scanner version: $OSV_VERSION"; then
  echo "skip: osv-scanner $OSV_VERSION already at $OSV_SCANNER"
else
  curl -fsSL -o "$TMP/osv-scanner" "$OSV_URL"
  check "$TMP/osv-scanner" 256 "$OSV_SHA"
  chmod +x "$TMP/osv-scanner"
  mv "$TMP/osv-scanner" "$OSV_SCANNER"
  echo "installed: osv-scanner v$OSV_VERSION -> $OSV_SCANNER"
fi
if [ "$MODE" = osv ]; then exit 0; fi

if [ -x "$JDK_DIR/bin/java" ]; then
  echo "skip: JDK already at $JDK_DIR"
else
  [ -e "$JDK_DIR" ] && { echo "error: $JDK_DIR exists but has no bin/java; remove it and run this script again" >&2; exit 1; }
  curl -fsSL -o "$TMP/jdk.tar.gz" "$JDK_URL"
  check "$TMP/jdk.tar.gz" 256 "$JDK_SHA"
  mkdir "$TMP/jdk"
  tar -xzf "$TMP/jdk.tar.gz" -C "$TMP/jdk"
  SRC="$(echo "$TMP"/jdk/*)"
  if [ "$JDK_OS" = mac ]; then SRC="$SRC/Contents/Home"; fi
  [ -x "$SRC/bin/java" ] || { echo "error: no bin/java in extracted JDK" >&2; exit 1; }
  mv "$SRC" "$JDK_DIR"
  echo "installed: JDK Temurin 21.0.12.1+1 -> $JDK_DIR"
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
