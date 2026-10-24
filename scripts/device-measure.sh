#!/bin/bash
# SDK device measurement (issue 24, ADR-0014). Local only: not run in CI.
#
# Usage: scripts/device-measure.sh <adb-serial> [scenario ...]
# Scenarios: normal offline burst   (default: all, in this order)
#
# Runs local.signaldock.device.MeasurePhases (sdk-android androidTest, debug build) on an emulator against the
# real Ktor + PostgreSQL server of a separate Compose project, through the pass-through fault proxy
# (scripts/e2e/fault-proxy.py), then reconciles the SDK's events with the server rows and writes
# perf/sdk-results/<api>-<scenario>/ (raw files, run.json, summary.md). It never runs `docker compose down`
# and never deletes a volume.
#
# Needs, started by you:
#   - a server stack with its own project name and ports, for example (env file outside the repo):
#       docker compose -p signaldock-sdkmeasure --env-file <file> up -d --build db server
#     <file> holds SIGNALDOCK_COLLECTION_KEY, SIGNALDOCK_QUERY_KEY, SIGNALDOCK_DB_*, SIGNALDOCK_SERVER_PORT, SIGNALDOCK_DB_PORT.
#     Point SIGNALDOCK_ENV_FILE at it. The default is .env, which uses the default stack and ports.
#   - one booted emulator (emulators only unless SIGNALDOCK_PHYSICAL_DEVICE=1, see below), for example:
#       $ANDROID_HOME/emulator/emulator -avd signaldock-api37 -no-window -no-audio -no-snapshot-save -port 5584 &
#   - macOS host (it records sysctl and sw_vers facts), JDK 21 (scripts/setup-toolchain.sh), ANDROID_HOME, python3.
# Release (R8) build, issue 33, ADR-0018: set MEASURE_BUILD=release. It runs the same phases (MeasureRunner) in the
# sdk/measure-app release variant (R8 minify on, debug-signed, not debuggable) and writes
# perf/sdk-results/api<api>-release-<scenario>/. It needs an emulator image where `adb root` works (google_apis, not
# Google Play), because a non-debuggable app has no `run-as`. Default (unset or `debug`) is the debug androidTest APK.
# Physical phone (ADR-0042): set SIGNALDOCK_PHYSICAL_DEVICE=1 and pass the phone serial. Debug build only (release needs
# `adb root`). The script uses `adb reverse` for the proxy port, removes it at the end, changes no device setting, and writes
# perf/sdk-results/api<api>-phone-<scenario>/. The test packages stay installed.
# Environment: SIGNALDOCK_PHYSICAL_DEVICE, MEASURE_BUILD, SIGNALDOCK_ENV_FILE, MEASURE_PROJECT (Compose project, default signaldock-sdkmeasure), MEASURE_PROXY_PORT (default 18091).
set -euo pipefail

[ "$(uname)" = Darwin ] || { echo "error: this script reads macOS host facts (sysctl, sw_vers); run it on macOS" >&2; exit 1; }
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
serial="${1:?usage: scripts/device-measure.sh <adb-serial> [normal|offline|burst ...]}"
shift || true
scenarios=("$@")
[ ${#scenarios[@]} -gt 0 ] || scenarios=(normal offline burst)
for s in "${scenarios[@]}"; do
  case "$s" in normal|offline|burst) ;; *) echo "error: unknown scenario '$s'; known: normal offline burst" >&2; exit 2 ;; esac
done
PHYS=0  # 1 on a personal phone (ADR-0042): opt-in only
case "$serial" in
  emulator-*) ;;
  *) if [ "${SIGNALDOCK_PHYSICAL_DEVICE:-}" = 1 ]; then
       PHYS=1; echo "notice: physical-device run on $serial (SIGNALDOCK_PHYSICAL_DEVICE=1); no device setting is changed"
     else echo "refusing $serial: emulators only" >&2; exit 1; fi ;;
esac

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export JAVA_HOME="${JAVA_HOME:-$ROOT/.tools/jdk-21.0.12.1+1}"
ADB="$ANDROID_HOME/platform-tools/adb"
adb() { "$ADB" -s "$serial" "$@"; }
ENV_FILE="${SIGNALDOCK_ENV_FILE:-.env}"
PROJECT="${MEASURE_PROJECT:-signaldock-sdkmeasure}"
PROXY_PORT="${MEASURE_PROXY_PORT:-18091}"
BUILD="${MEASURE_BUILD:-debug}"
case "$BUILD" in
  debug)
    PKG=local.signaldock.test
    RUNNER="$PKG/androidx.test.runner.AndroidJUnitRunner"
    APK=sdk/sdk-android/build/outputs/apk/androidTest/debug/sdk-android-debug-androidTest.apk
    PREFIX="" ;;
  release)
    PKG=local.signaldock.measureapp
    RUNNER="$PKG/.MeasureInstrumentation"
    APK=sdk/measure-app/build/outputs/apk/release/measure-app-release.apk
    MAPPING=sdk/measure-app/build/outputs/mapping/release/mapping.txt
    PREFIX="release-" ;;
  *) echo "error: MEASURE_BUILD must be debug or release" >&2; exit 2 ;;
esac
if [ "$PHYS" = 1 ]; then
  [ "$BUILD" = debug ] || { echo "error: MEASURE_BUILD=release needs adb root; it is not allowed on a physical device (ADR-0042)" >&2; exit 2; }
  PREFIX="phone-"
fi

[ -f "$ENV_FILE" ] || { echo "error: env file $ENV_FILE not found; see the header of this script" >&2; exit 1; }
set -a; . "$ENV_FILE"; set +a
SERVER_PORT="${SIGNALDOCK_SERVER_PORT:-8080}"
compose() { docker compose -p "$PROJECT" --env-file "$ENV_FILE" "$@"; }
psql_ids() { compose exec -T db psql -U "$SIGNALDOCK_DB_USER" -d "${SIGNALDOCK_DB_NAME:-signaldock}" -At -c "SELECT event_id FROM events WHERE name = 'm_$1' ORDER BY event_id"; }
compose ps --status running --services | grep -qx server || { echo "error: server of project $PROJECT is not running; see the header of this script" >&2; exit 1; }
"$ADB" -s "$serial" get-state >/dev/null 2>&1 || { echo "error: no emulator $serial" >&2; exit 1; }
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 2; done

if [ "$BUILD" = release ]; then
  sdk/gradlew -p sdk :measure-app:assembleRelease --no-daemon -q
  # A non-debuggable app has no run-as: read its data directory as root (emulator images with `adb root` only).
  "$ADB" -s "$serial" root >/dev/null 2>&1 || true
  "$ADB" -s "$serial" wait-for-device
  [ "$(adb shell id -u | tr -d '\r')" = 0 ] || { echo "error: adb root is not available on $serial; use a google_apis image, not Google Play" >&2; exit 1; }
  until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 2; done
else
  sdk/gradlew -p sdk :sdk-android:assembleDebugAndroidTest --no-daemon -q
fi
adb install -r -t "$APK" >/dev/null

api="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
if [ "$PHYS" = 1 ]; then avd="$(adb shell getprop ro.product.model | tr -d '\r') (physical)"
else avd="$(adb emu avd name 2>/dev/null | head -1 | tr -d '\r')"; fi
echo "device: $serial  AVD $avd  $(adb shell getprop ro.product.model | tr -d '\r')  API $api  ($BUILD build)"

# unreverse: remove the adb reverse of the proxy port (phone only; errors ignored).
unreverse() { if [ "$PHYS" = 1 ]; then adb reverse --remove "tcp:$PROXY_PORT" >/dev/null 2>&1 || true; fi; }

# phase <name> <endpoint> <runId>: one instrumentation run in a fresh process.
phase() {
  local out
  if [ "$BUILD" = release ]; then
    out="$(adb shell am instrument -w -e phase "$1" -e endpoint "$2" -e key "$SIGNALDOCK_COLLECTION_KEY" -e runId "$3" "$RUNNER" | tr -d '\r')"
    ok='^INSTRUMENTATION_CODE: -1$'
  else
    out="$(adb shell am instrument -w -e class local.signaldock.device.MeasurePhases -e phase "$1" -e endpoint "$2" -e key "$SIGNALDOCK_COLLECTION_KEY" -e runId "$3" "$RUNNER" | tr -d '\r')"
    ok='^OK ('
  fi
  if ! grep -q "$ok" <<<"$out"; then echo "FAILED phase $1:" >&2; echo "$out" >&2; return 1; fi
  echo "  phase $1 done"
}

run_scenario() {
  local sc="$1" final="perf/sdk-results/api$api-$PREFIX$1" dir runId="r$(date -u +%Y%m%d%H%M%S)$1" ok=0
  dir="$(mktemp -d)"  # published to $final only after collection, so a failed setup keeps the previous run
  echo "== $sc (run $runId)"
  adb shell pm clear "$PKG" >/dev/null
  python3 scripts/e2e/fault-proxy.py --listen "127.0.0.1:$PROXY_PORT" --upstream "127.0.0.1:$SERVER_PORT" --log "$dir/proxy.jsonl" &
  PROXY_PID=$!
  trap 'kill "$PROXY_PID" 2>/dev/null || true; unreverse' EXIT  # a failing command below must not leave the proxy running
  for _ in $(seq 50); do curl -fs -o /dev/null "http://127.0.0.1:$PROXY_PORT/__fault/mode" && break; sleep 0.2; done
  local endpoint="http://10.0.2.2:$PROXY_PORT" load_before load_after started ended
  if [ "$PHYS" = 1 ]; then
    adb reverse "tcp:$PROXY_PORT" "tcp:$PROXY_PORT" >/dev/null
    endpoint="http://127.0.0.1:$PROXY_PORT"
  fi
  load_before="$(sysctl -n vm.loadavg)"
  started="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  case "$sc" in
    normal|burst) phase "$sc" "$endpoint" "$runId" && ok=1 ;;
    offline)
      phase offline_store "http://127.0.0.1:9" "$runId" && {
        adb shell am force-stop "$PKG"; sleep 2
        phase offline_drain "$endpoint" "$runId" && ok=1
      } ;;
  esac
  ended="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  load_after="$(sysctl -n vm.loadavg)"
  kill "$PROXY_PID" 2>/dev/null || true
  wait "$PROXY_PID" 2>/dev/null || true
  unreverse
  trap - EXIT
  # Pull what exists even when a phase failed, so that the evidence survives the next `pm clear`.
  if [ "$BUILD" = release ]; then
    for f in $(adb shell ls "/data/data/$PKG/files/measure/$runId" 2>/dev/null | tr -d '\r'); do
      adb exec-out cat "/data/data/$PKG/files/measure/$runId/$f" > "$dir/$f" || rm -f "$dir/$f"
    done
  else
    for f in $(adb shell run-as "$PKG" ls "files/measure/$runId" 2>/dev/null | tr -d '\r'); do
      adb exec-out run-as "$PKG" cat "files/measure/$runId/$f" > "$dir/$f" || rm -f "$dir/$f"
    done
  fi
  psql_ids "$runId" > "$dir/db-ids.txt" || { echo "warning: could not read event IDs from the database; the run is invalid" >&2; rm -f "$dir/db-ids.txt"; }
  python3 - "$dir" "$sc" "$runId" "$ok" "$started" "$ended" "$load_before" "$load_after" "$avd" "$serial" "$BUILD" "${MAPPING:-}" "$APK" "$PHYS" <<'EOF'
import hashlib, json, re, subprocess, sys
d, sc, run_id, ok, t0, t1, l0, l1, avd, serial, build, mapping, apk, phys = sys.argv[1:]
def sh(*a): return subprocess.run(a, capture_output=True, text=True).stdout.strip()
def sha(path): return hashlib.sha256(open(path, "rb").read()).hexdigest()
def r8_evidence(mapping, apk):
    """The measured APK went through R8: its mapping file renames SDK classes (ADR-0018). The mapping itself is not committed."""
    lines = open(mapping).read().splitlines()
    # SDK classes only: the measure app and the shared runner are in local.signaldock.measureapp and local.signaldock.measure.
    sdk = [l for l in lines if re.match(r"local\.signaldock\.[\w.$]+ -> ", l) and not re.match(r"local\.signaldock\.measure(app)?\.", l)]
    renamed = [l for l in sdk if l.split(" -> ")[1].rstrip(":") != l.split(" -> ")[0] and "R8$$REMOVED" not in l]
    return {"minifyEnabled": True, "shrinkResources": True, "apk_sha256": sha(apk), "mapping_sha256": sha(mapping), "mapping_lines": len(lines),
            "sdk_classes_in_mapping": len(sdk), "sdk_classes_renamed": len(renamed), "sdk_classes_removed": len(sdk) - len([l for l in sdk if "R8$$REMOVED" not in l]),
            "example": next((l for l in renamed if l.startswith("local.signaldock.SignalDock ")), None)}
json.dump({
    "scenario": sc, "run_id": run_id, "completed_phases": ok == "1", "started_utc": t0, "ended_utc": t1,
    "avd": avd, "serial": serial, **({"physical_device": True} if phys == "1" else {}),
    "build_type": build,
    "build": "debug androidTest APK: SDK library debug variant, debuggable, no R8/minify (ADR-0014)" if build == "debug" else
             "release measure app (sdk/measure-app): SDK as dependency, R8 minify and shrinkResources on, debug-signed release variant, not debuggable (ADR-0018)",
    **({"r8": r8_evidence(mapping, apk)} if build == "release" else {}),
    "server": {"stack": "Docker Compose, real Ktor server + PostgreSQL 17.6, default product settings, project separate from the default stack",
               "path": ("device -> 127.0.0.1 (adb reverse) -> fault-proxy (pass mode) -> 127.0.0.1 -> server container" if phys == "1" else
                        "device -> 10.0.2.2 -> fault-proxy (pass mode) -> 127.0.0.1 -> server container")},
    "host": {"model": sh("sysctl", "-n", "machdep.cpu.brand_string"), "cpus": sh("sysctl", "-n", "hw.ncpu"), "os": sh("sw_vers", "-productVersion"),
             "loadavg_before": l0, "loadavg_after": l1},
    "git_revision": sh("git", "rev-parse", "--short", "HEAD") + (" (dirty)" if sh("git", "status", "--porcelain", "--untracked-files=no") else ""),
}, open(d + "/meta.json", "w"), indent=2)
EOF
  local rc=0
  python3 perf/sdk_summarize.py "$dir" || rc=1  # exits 1 for a fail or invalid verdict; the run is kept either way
  rm -rf "$final"; mkdir -p perf/sdk-results; cp -R "$dir" "$final"; chmod 755 "$final"; rm -rf "$dir"
  echo "  published $final"
  return "$rc"
}

fail=0
for s in "${scenarios[@]}"; do run_scenario "$s" || fail=1; done
python3 perf/sdk_summarize.py --all perf/sdk-results
exit "$fail"
