#!/bin/bash
# iOS ticket 13 (ADR-0047 D11): builds dist/signaldock-sdk-ios-<version>.zip and its SHA-256 file.
# The ZIP holds one folder, SignalDock/, with Package.swift, Sources/ (incl. PrivacyInfo.xcprivacy), and LICENSE.
# It is reproducible: tracked files only, sorted, fixed time stamp, fixed modes, no extra attributes.
# It does not create a tag, push, or call `gh release`.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="${1:-}"
if [ -z "$VERSION" ]; then
  echo "usage: scripts/build-ios-release-zip.sh <version> [--check]   (for example 0.1.0; --check only compares it with SignalDockSDK.version)" >&2
  exit 1
fi

SDK_VERSION="$(sed -n 's/^  public static let version = "\(.*\)"$/\1/p' "$ROOT/sdk-ios/Sources/SignalDock/SignalDockSDK.swift")"
if [ "$VERSION" != "$SDK_VERSION" ]; then
  echo "error: version '$VERSION' does not match SignalDockSDK.version '$SDK_VERSION' in sdk-ios/Sources/SignalDock/SignalDockSDK.swift" >&2
  exit 1
fi

# The release workflow runs --check first, so a wrong tag fails before any gate or build.
if [ "${2:-}" = "--check" ]; then
  echo "ok: version '$VERSION' matches SignalDockSDK.version"
  exit 0
fi

NAME="signaldock-sdk-ios-$VERSION.zip"
DIST="$ROOT/dist"
mkdir -p "$DIST"
rm -f "$DIST/$NAME" "$DIST/$NAME.sha256"

cd "$ROOT"
# Tracked files only: build output, .DS_Store, and extended attributes of the working tree never enter the ZIP.
LIST="$(mktemp)"
trap 'rm -f "$LIST"' EXIT
git ls-files -z -- sdk-ios/Package.swift sdk-ios/Sources LICENSE > "$LIST"
python3 -I - "$DIST/$NAME" "$LIST" <<'PY'
import sys, zipfile
out, listing = sys.argv[1], sys.argv[2]
paths = sorted(p for p in open(listing, "rb").read().decode().split("\0") if p)
FIXED = (2026, 1, 1, 0, 0, 0)  # ZIP time stamps start in 1980; any fixed value works
TEST_TARGET = '    .testTarget(name: "SignalDockTests", dependencies: ["SignalDock"]),\n'
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for p in paths:
        dest = "SignalDock/" + (p[len("sdk-ios/"):] if p.startswith("sdk-ios/") else p)
        data = open(p, "rb").read()
        if p == "sdk-ios/Package.swift":
            # The ZIP has no Tests/ folder; a manifest with a test target and no test folder does not load.
            text = data.decode()
            if text.count(TEST_TARGET) != 1:
                sys.exit("error: sdk-ios/Package.swift has not exactly one SignalDockTests line; update this script")
            data = text.replace(TEST_TARGET, "").encode()
        info = zipfile.ZipInfo(dest, FIXED)
        info.compress_type = zipfile.ZIP_DEFLATED
        info.create_system = 3
        info.external_attr = 0o100644 << 16
        z.writestr(info, data, compresslevel=9)
PY

# Relative file name in the checksum file, so `shasum -a 256 -c` works from dist/.
(cd "$DIST" && shasum -a 256 "$NAME" > "$NAME.sha256" && shasum -a 256 -c "$NAME.sha256")

echo "ZIP:    $DIST/$NAME"
echo "SHA256: $DIST/$NAME.sha256"
cat "$DIST/$NAME.sha256"
