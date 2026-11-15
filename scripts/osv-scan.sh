#!/bin/bash
# Gate R2 (ADR-0049 decision 9): scan the npm lockfiles and the four Gradle verification-metadata files with the pinned
# OSV-Scanner (scripts/setup-toolchain.sh osv). A finding that is not in osv-scanner.toml fails the run.
# Gradle: OSV-Scanner reads gradle/verification-metadata.xml as a lockfile, so the whole resolved tree is scanned,
# including the Gradle plugin classpath, which the SDK and the server do not ship. An ignore needs a reason and an
# `ignoreUntil` at most 30 days ahead (checked by scripts/check-repo-rules.sh).
# Usage: scripts/osv-scan.sh      (needs the network: osv.dev)
set -euo pipefail
cd "$(dirname "$0")/.."

OSV=.tools/osv-scanner
if ! [ -x "$OSV" ] || ! "$OSV" --version 2>/dev/null | head -1 | grep -qx 'osv-scanner version: 2\.7\.1'; then
  echo "error: osv-scanner 2.7.1 is not at $OSV; run scripts/setup-toolchain.sh osv" >&2
  exit 1
fi

LOCKFILES=(
  package-lock.json
  dashboard/package-lock.json
  perf/report/package-lock.json
  sdk/gradle/verification-metadata.xml
  server/gradle/verification-metadata.xml
  contract-check/gradle/verification-metadata.xml
  compatibility/consumer-android/gradle/verification-metadata.xml
)
args=()
for f in "${LOCKFILES[@]}"; do
  [ -f "$f" ] || { echo "error: $f is missing; update LOCKFILES in $0" >&2; exit 1; }
  args+=(-L "$f")
done

# Exit 0: no finding. Exit 1: a finding. Anything else (127, 128): the scan did not run. No retry (ADR-0049 decision 5).
"$OSV" scan source --config osv-scanner.toml "${args[@]}"
