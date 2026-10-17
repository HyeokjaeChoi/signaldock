#!/bin/bash
# Device verification of SDK durability (issue 20, ADR-0011). Local only: not run in CI.
#
# Usage: scripts/device-durability.sh <adb-serial> [case ...]
# Cases: offline kill restart update clear reinstall restore airplane fulldisk workmanager optout   (default: all)
#
# Each case drives phases of local.signaldock.device.DurabilityPhases (sdk-android androidTest) with real
# process boundaries between the instrumentation runs: am force-stop (SIGKILL), adb install -r, pm clear,
# uninstall + install, and bmgr backup + restore. The last three cases (FaultPhases, ADR-0029) switch airplane
# mode, fill the data partition and lock the WorkManager database; a trap restores network, space and the host server.
# In-process cases run with
#   sdk/gradlew -p sdk :sdk-android:connectedDebugAndroidTest
# Needs: a running emulator or device, ANDROID_HOME (or adb on PATH), JAVA_HOME or .tools (setup-toolchain.sh).
set -euo pipefail

cd "$(dirname "$0")/.."
serial="${1:?usage: scripts/device-durability.sh <adb-serial> [case ...]}"
shift || true
cases=("$@")
[ ${#cases[@]} -gt 0 ] || cases=(offline kill restart update clear reinstall restore airplane fulldisk workmanager optout)

# The cases switch Backup Manager off and select the local transport: that must never touch a physical device.
case "$serial" in emulator-*) ;; *) echo "refusing $serial: emulators only (backup settings are changed)" >&2; exit 1 ;; esac

adb_bin="${ANDROID_HOME:+$ANDROID_HOME/platform-tools/}adb"
adb() { "$adb_bin" -s "$serial" "$@"; }

if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME="$PWD/.tools/$(ls .tools | grep '^jdk-')"
  export JAVA_HOME
fi
sdk/gradlew -p sdk :sdk-android:assembleDebugAndroidTest --no-daemon -q

APK=sdk/sdk-android/build/outputs/apk/androidTest/debug/sdk-android-debug-androidTest.apk
PKG=local.signaldock.test
RUNNER="$PKG/androidx.test.runner.AndroidJUnitRunner"

echo "device: $serial  $(adb shell getprop ro.product.model | tr -d '\r')  API $(adb shell getprop ro.build.version.sdk | tr -d '\r')  (test APK: debug build)"

# Auto-restore at install would bring back host data of an earlier backup in the cases that are not about
# restore, so Backup Manager stays off except inside case_restore.
backup_off() { adb shell bmgr enable false >/dev/null 2>&1 || true; }
backup_off
# Restore every global state that a case may have changed, however the script ends.
host_server_pid=""
restore_device() {
  [ -z "$host_server_pid" ] || kill "$host_server_pid" 2>/dev/null || true
  backup_off
  adb shell cmd connectivity airplane-mode disable >/dev/null 2>&1 || true
  adb shell run-as "$PKG" rm -f files/filler.bin >/dev/null 2>&1 || true
}
trap restore_device EXIT

fresh_install() {
  adb uninstall "$PKG" >/dev/null 2>&1 || true
  adb install -t "$APK" >/dev/null
}

# phase <name> [-e key value ...]: runs one instrumentation phase, prints its status lines, returns its output in $out.
phase() {
  local name="$1"; shift
  out="$(adb shell am instrument -w -e class "${phase_class:-local.signaldock.device.DurabilityPhases}" -e phase "$name" "$@" "$RUNNER" | tr -d '\r')"
  if ! grep -q '^OK (' <<<"$out" || ! grep -q 'INSTRUMENTATION_STATUS: status=pass' <<<"$out"; then
    echo "FAILED phase $name:" >&2
    echo "$out" >&2
    exit 1
  fi
  echo "  phase $name: $(grep -o 'INSTRUMENTATION_STATUS: [a-zA-Z]*=[^ ]*' <<<"$out" | grep -v 'eventIds\|phase=\|status=' | sed 's/INSTRUMENTATION_STATUS: //' | tr '\n' ' ')"
}
# The first status line of a run is prefixed with the test class name, so the key is not anchored.
get() { sed -n "s/.*INSTRUMENTATION_STATUS: $1=//p" <<<"$out" | head -1; }

seed() {
  fresh_install
  phase install_seed
  installation_id="$(get installationId)"
  event_ids="$(get eventIds)"
  [ -n "$installation_id" ] && [ -n "$event_ids" ] || { echo "seed phase reported no installation id or event ids" >&2; exit 1; }
}
check() { # expect marker
  phase install_check -e expect "$1" -e marker "$2" -e prevInstallationId "$installation_id" -e prevEventIds "$event_ids"
}

case_offline() {
  echo "== offline: 1,000 events stored offline, app process killed, restart, reconnect, delivery"
  fresh_install
  phase offline_store
  pid1="$(get pid)"
  adb shell am force-stop "$PKG"
  sleep 2
  phase offline_recover
  pid2="$(get pid)"
  [ "$pid1" != "$pid2" ] || { echo "same process id $pid1: no restart" >&2; exit 1; }
  echo "  process $pid1 killed, recovered in process $pid2"
}

case_kill() {
  echo "== kill: SIGKILL during a burst of track() calls"
  fresh_install
  adb shell am instrument -w -e class local.signaldock.device.DurabilityPhases -e phase kill_burst_store "$RUNNER" >/dev/null &
  bg=$!
  sleep 12
  # Prove collection is still running on the device right before the kill: the instrumented process is alive
  # and the fsynced acked.log (written by kill_burst_store after each reported success) is still growing.
  size1="$(adb shell run-as "$PKG" wc -c files/harness/acked.log 2>/dev/null | tr -d '\r' | awk '{print $1}')"
  sleep 1
  size2="$(adb shell run-as "$PKG" wc -c files/harness/acked.log 2>/dev/null | tr -d '\r' | awk '{print $1}')"
  pid="$(adb shell pidof "$PKG" | tr -d '\r')"
  if [ -z "$pid" ] || [ -z "${size1:-}" ] || [ -z "${size2:-}" ] || [ "$size2" -le "$size1" ]; then
    echo "kill_burst_store was not collecting at kill time (pid='${pid:-}', acked.log ${size1:-?} -> ${size2:-?} bytes)" >&2
    # Do not leave the phase running into the next case.
    adb shell am force-stop "$PKG"
    wait "$bg" || true
    exit 1
  fi
  echo "  collecting before kill: pid $pid, acked.log $size1 -> $size2 bytes"
  adb shell am force-stop "$PKG"
  wait "$bg" || true
  sleep 2
  phase kill_burst_verify
}

case_restart() { echo "== restart: process killed, same installation"; seed; adb shell am force-stop "$PKG"; sleep 2; check kept present; }
case_update() { echo "== update: adb install -r over the installed package"; seed; adb install -r -t "$APK" >/dev/null; sleep 2; check kept present; }
case_clear() { echo "== clear: pm clear (app data cleared)"; seed; adb shell pm clear "$PKG" >/dev/null; check fresh absent; }
case_reinstall() { echo "== reinstall: uninstall, install"; seed; adb uninstall "$PKG" >/dev/null; adb install -t "$APK" >/dev/null; check fresh absent; }

case_optout() {
  echo "== optout: setOptOut(true), process killed, the marker file keeps the opt-out; then setOptOut(false)"
  fresh_install
  phase optout_set
  adb shell am force-stop "$PKG"
  sleep 2
  phase optout_check
  adb shell am force-stop "$PKG"
  sleep 2
  phase optout_off
}

case_restore() {
  echo "== restore: Auto Backup to the local transport, uninstall, install, bmgr restore"
  seed
  adb shell bmgr enable true >/dev/null
  transport="$(adb shell bmgr list transports | tr -d '\r' | grep -i 'LocalTransport' | head -1 | sed 's/^[ *]*//')"
  adb shell bmgr transport "$transport" >/dev/null
  adb shell bmgr wipe "$transport" "$PKG" >/dev/null 2>&1 || true
  echo "  transport: $transport"
  adb shell bmgr backupnow "$PKG" | tr -d '\r' | grep -q "Package $PKG with result: Success" || { echo "backup did not succeed" >&2; exit 1; }
  # LocalTransport names its current restore set with token 1; list order does not mark it.
  adb shell bmgr list sets | tr -d '\r' | awk '{print $1}' | grep -qx 1 || { echo "restore set token 1 not found: backup set missing" >&2; exit 1; }
  token=1
  adb uninstall "$PKG" >/dev/null
  adb install -t "$APK" >/dev/null
  adb shell bmgr restore "$token" "$PKG" >/dev/null
  # Restore is asynchronous: wait for the host marker file that Auto Backup carried over.
  for _ in $(seq 1 60); do
    adb shell run-as "$PKG" ls files/harness/marker.txt >/dev/null 2>&1 && break
    sleep 1
  done
  adb shell run-as "$PKG" ls files/harness/marker.txt >/dev/null 2>&1 || { echo "restore did not bring back host data: inconclusive" >&2; exit 1; }
  check fresh present
  backup_off
}

fault() { phase_class=local.signaldock.device.FaultPhases phase "$@"; }

case_airplane() {
  echo "== airplane: airplane mode on while tracking (NETWORK, queue kept), off again (delivery recovers)"
  fresh_install
  # The server runs on the host: the emulator reaches it at 10.0.2.2, a route that airplane mode removes.
  local port=18099 log
  log="$(mktemp)"
  python3 scripts/ack-server.py "$port" "$log" &
  host_server_pid=$! # the trap kills it if a phase fails
  sleep 1
  kill -0 "$host_server_pid" 2>/dev/null || { echo "host ACK server did not start (port $port in use?)" >&2; host_server_pid=""; exit 1; }
  fault airplane_mode -e endpoint "http://10.0.2.2:$port"
  kill "$host_server_pid" 2>/dev/null || true
  wait "$host_server_pid" 2>/dev/null || true
  host_server_pid=""
  local ids unique
  ids="$(wc -l <"$log" | tr -d ' ')"; unique="$(sort -u "$log" | wc -l | tr -d ' ')"
  rm -f "$log"
  echo "  host server received $ids event IDs, $unique unique"
  [ "$unique" -eq 25 ] || { echo "expected 25 unique event IDs at the host server" >&2; exit 1; }
}

case_fulldisk() {
  echo "== fulldisk: data partition filled until track() returns StorageError, space freed, delivery recovers"
  fresh_install
  echo "  free before: $(adb shell df -k /data | tail -1 | awk '{print $4}') KiB"
  fault full_disk
  echo "  free after: $(adb shell df -k /data | tail -1 | awk '{print $4}') KiB"
}

case_workmanager() {
  echo "== workmanager: WorkManager database locked against writes, so every enqueue fails"
  fresh_install
  # The phase holds the lock inside its own process: nothing to restore on the device.
  fault workmanager_fault
}

for c in "${cases[@]}"; do "case_$c"; done
echo "all requested cases passed on $serial"
