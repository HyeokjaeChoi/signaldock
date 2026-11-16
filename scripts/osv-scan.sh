#!/bin/bash
# Gate R2 (ADR-0049 decision 9): scan the npm lockfiles and the four Gradle verification-metadata files with the pinned
# OSV-Scanner (scripts/setup-toolchain.sh osv). A finding that no osv-scanner.toml ignores fails the run.
# An ignore covers one tree only (see the loop below): server/, contract-check/, dashboard/, and perf/report/ have none.
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
# `-L` does not look for a config, so each file gets the osv-scanner.toml in its own folder, if any. A file with none,
# such as server/gradle/verification-metadata.xml, has no ignore. No ignore is shared between trees.
status=0
for f in "${LOCKFILES[@]}"; do
  [ -f "$f" ] || { echo "error: $f is missing; update LOCKFILES in $0" >&2; exit 1; }
  cfg=()
  [ -f "$(dirname "$f")/osv-scanner.toml" ] && cfg=(--config "$(dirname "$f")/osv-scanner.toml")
  echo "=== $f ${cfg[*]:-(no ignores)}"
  # Exit 0: no finding. Exit 1: a finding. Anything else (127, 128): the scan did not run. No retry (ADR-0049 decision 5).
  rc=0
  "$OSV" scan source ${cfg[@]+"${cfg[@]}"} -L "$f" || rc=$?
  case $rc in 0) ;; 1) status=1 ;; *) echo "error: osv-scanner did not run for $f (exit $rc)" >&2; exit "$rc" ;; esac
done
exit "$status"
