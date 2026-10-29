#!/bin/bash
# iOS ticket 13 (ADR-0047 D11): builds dist/signaldock-sdk-ios-<version>.zip and its SHA-256 file.
# The ZIP holds one folder, SignalDock/, with Package.swift, Sources/ (incl. PrivacyInfo.xcprivacy), and LICENSE.
# It is reproducible and reads the files of HEAD, not the working tree: a dirty tree does not change the ZIP. It sorts
# the paths, uses a fixed time stamp and fixed modes, and adds no extended attributes.
# It does not create a tag, push, or call `gh release`.
# Usage: scripts/build-ios-release-zip.sh <version> [--check]   or   scripts/build-ios-release-zip.sh --check-manifest
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# The ZIP has no Tests/ folder; a manifest with a test target and no test folder does not load (ADR-0047 Ticket 13).
TEST_TARGET='    .testTarget(name: "SignalDockTests", dependencies: ["SignalDock"]),'

# PR-time check (called by check-repo-rules.sh): the staged manifest still has the test-target line exactly once.
if [ "${1:-}" = "--check-manifest" ]; then
  count="$(git -C "$ROOT" show :sdk-ios/Package.swift | grep -cxF -- "$TEST_TARGET" || true)"
  if [ "$count" != 1 ]; then
    echo "error: sdk-ios/Package.swift has $count lines equal to the SignalDockTests line that scripts/build-ios-release-zip.sh removes; the manifest and the script must change together" >&2
    exit 1
  fi
  echo "ok: sdk-ios/Package.swift has the test-target line exactly once"
  exit 0
fi

VERSION="${1:-}"
if [ -z "$VERSION" ]; then
  echo "usage: scripts/build-ios-release-zip.sh <version> [--check] | --check-manifest   (for example 0.1.0; --check only compares it with SignalDockSDK.version)" >&2
  exit 1
fi

SDK_VERSION="$(git -C "$ROOT" show HEAD:sdk-ios/Sources/SignalDock/SignalDockSDK.swift | sed -n 's/^  public static let version = "\(.*\)"$/\1/p')"
if [ "$VERSION" != "$SDK_VERSION" ]; then
  echo "error: version '$VERSION' does not match SignalDockSDK.version '$SDK_VERSION' in sdk-ios/Sources/SignalDock/SignalDockSDK.swift (HEAD)" >&2
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
python3 -I - "$DIST/$NAME" "$TEST_TARGET" <<'PY'
import subprocess, sys, zipfile
out, test_target = sys.argv[1], sys.argv[2] + "\n"

def git(*args):
    return subprocess.run(["git", *args], check=True, stdout=subprocess.PIPE).stdout

# Entries of HEAD: "<mode> blob <sha>\t<path>". A link (120000) or an executable (100755) is a surprise: stop.
entries = []
for rec in git("ls-tree", "-r", "-z", "HEAD", "--", "sdk-ios/Package.swift", "sdk-ios/Sources", "LICENSE").decode().split("\0"):
    if not rec:
        continue
    meta, path = rec.split("\t", 1)
    mode, _, sha = meta.split(" ")
    if mode != "100644":
        sys.exit(f"error: {path} has git mode {mode}; the ZIP ships only plain files (100644)")
    entries.append((path, sha))
if not entries:
    sys.exit("error: no files found at HEAD")
FIXED = (2026, 1, 1, 0, 0, 0)  # ZIP time stamps start in 1980; any fixed value works
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for path, sha in sorted(entries):
        dest = "SignalDock/" + (path[len("sdk-ios/"):] if path.startswith("sdk-ios/") else path)
        data = git("cat-file", "blob", sha)
        if path == "sdk-ios/Package.swift":
            text = data.decode()
            if text.count(test_target) != 1:
                sys.exit("error: sdk-ios/Package.swift has not exactly one SignalDockTests line; update this script")
            data = text.replace(test_target, "").encode()
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
