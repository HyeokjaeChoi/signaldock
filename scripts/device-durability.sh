#!/bin/bash
# Device verification of SDK durability (issue 20, ADR-0011). Runs locally, and in the nightly CI tier on API 24 and 37
# emulators as the job `device-durability` of .github/workflows/nightly.yml (ADR-0049 gates A5, A6, A4).
#
# Usage: scripts/device-durability.sh <adb-serial> [case ...]
# Cases: the list `all_cases` below (default: all). Each case runs in its own subshell: one failing case does not stop the others. The exit code is 1
#        if any case did not pass. `powerloss` (ADR-0049 decision 6) SIGKILLs the emulator process and cold-boots it again from
#        the same data partition, so the serial must be `emulator-<console port>` of an emulator that this script may restart
#        (needs `lsof`; EMULATOR_OPTS overrides the options of the restart). POWERLOSS_SKIP_CHECKPOINT=1 skips the checkpoint:
#        the negative control that shows the gate can fail. `maincost` is gate A4: p99 of `track()` on the main thread.
# Result files (optional): DURABILITY_RESULT_JSON gets [{"name","result":"pass"|"fail"|"invalid","seconds"}] (input of
# scripts/nightly-summary.py); DURABILITY_NUMBERS gets key=value lines (--number arguments of the same script).
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
all_cases=(offline kill restart update clear reinstall restore airplane fulldisk workmanager optout powerloss maincost)
cases=("$@")
[ ${#cases[@]} -gt 0 ] || cases=("${all_cases[@]}")

# The cases switch Backup Manager off and select the local transport: that must never touch a physical device.
case "$serial" in emulator-*) ;; *) echo "refusing $serial: emulators only (backup settings are changed)" >&2; exit 1 ;; esac

adb_bin="${ANDROID_HOME:+$ANDROID_HOME/platform-tools/}adb"
adb() { "$adb_bin" -s "$serial" "$@"; }

if [ -z "${JAVA_HOME:-}" ]; then
  jdks=("$PWD"/.tools/jdk-*)
  export JAVA_HOME="${jdks[0]}"
  [ -x "$JAVA_HOME/bin/java" ] || { echo "error: no JDK at $JAVA_HOME; run scripts/setup-toolchain.sh or set JAVA_HOME" >&2; exit 1; }
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
host_server_pid="" writer_pid="" tmp_files=()
restore_device() {
  [ -z "$host_server_pid" ] || kill "$host_server_pid" 2>/dev/null || true
  [ -z "$writer_pid" ] || { kill "$writer_pid" 2>/dev/null || true; wait "$writer_pid" 2>/dev/null || true; }
  rm -f ${tmp_files[@]+"${tmp_files[@]}"}
  backup_off
  adb shell cmd connectivity airplane-mode disable >/dev/null 2>&1 || true
  adb shell svc data enable >/dev/null 2>&1 || true
  adb shell svc wifi enable >/dev/null 2>&1 || true
  adb shell run-as "$PKG" rm -f files/filler.bin >/dev/null 2>&1 || true
}
# A run that is not valid (a harness error, not a product failure) writes its reason here and exits 1; the main loop reads it.
invalid_marker="$(mktemp)"
# restore_device is also the trap of each case subshell, which must not remove the marker: only this outer trap does.
outer_exit() { restore_device; rm -f "$invalid_marker"; }
trap outer_exit EXIT

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
    # A harness error (for example a checkpoint that stayed busy) makes the run invalid, not a product failure.
    if grep -q 'INSTRUMENTATION_STATUS: status=invalid' <<<"$out"; then echo "invalid phase $name" >"$invalid_marker"; fi
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
  pid1="$(get pid)"
  adb shell am force-stop "$PKG"
  sleep 2
  phase optout_check
  pid2="$(get pid)"
  adb shell am force-stop "$PKG"
  sleep 2
  phase optout_off
  pid3="$(get pid)"
  { [ -n "$pid1" ] && [ "$pid1" != "$pid2" ] && [ "$pid1" != "$pid3" ] && [ "$pid2" != "$pid3" ]; } ||
    { echo "opt-out phases did not run in distinct processes: '$pid1' '$pid2' '$pid3'" >&2; exit 1; }
  echo "  processes $pid1, $pid2, $pid3"
}

case_restore() {
  echo "== restore: Auto Backup to the local transport, uninstall, install, bmgr restore"
  seed
  adb shell bmgr enable true >/dev/null
  transport="$(adb shell bmgr list transports | tr -d '\r' | grep -i 'LocalTransport' | head -1 | sed 's/^[ *]*//')"
  adb shell bmgr transport "$transport" >/dev/null
  adb shell bmgr wipe "$transport" "$PKG" >/dev/null 2>&1 || true
  echo "  transport: $transport"
  local backup_out
  backup_out="$(adb shell bmgr backupnow "$PKG" 2>&1 | tr -d '\r')"
  grep -q "Package $PKG with result: Success" <<<"$backup_out" || { echo "backup did not succeed; bmgr said: $backup_out" >&2; adb shell bmgr list transports 2>&1 | tr -d '\r' >&2; exit 1; }
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
  log="$(mktemp)"; tmp_files+=("$log")
  python3 scripts/ack-server.py "$port" "$log" &
  host_server_pid=$! # the trap kills it if a phase fails
  sleep 1
  kill -0 "$host_server_pid" 2>/dev/null || { echo "host ACK server did not start (port $port in use?)" >&2; host_server_pid=""; exit 1; }
  fault airplane_mode -e endpoint "http://10.0.2.2:$port"
  kill "$host_server_pid" 2>/dev/null || true
  wait "$host_server_pid" 2>/dev/null || true
  host_server_pid=""
  local ids unique
  # The host server logs "eventId<TAB>status<TAB>name" per received event (scripts/ack-server.py).
  ids="$(wc -l <"$log" | tr -d ' ')"; unique="$(cut -f1 "$log" | sort -u | wc -l | tr -d ' ')"
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


powerloss() { phase_class=local.signaldock.device.PowerLossPhases phase "$@"; }

# Set sizes of PowerLossPhases and of scripts/powerloss-verify.py (one place: this script).
PL_PRE=40 PL_LOSTACK=30 PL_PENDING=30 PL_TAIL=20

# SIGKILL of the emulator process loses the guest page cache, as a power cut does; the data partition file on the host
# stays. Then a cold boot (no snapshot is loaded or saved; no -wipe-data).
emulator_kill_and_cold_boot() {
  local port="${serial#emulator-}" avd pid
  avd="$(adb emu avd name 2>/dev/null | head -1 | tr -d '\r' || true)"
  pid="$(lsof -nP -iTCP:"$port" -sTCP:LISTEN -t 2>/dev/null | head -1 || true)"
  [ -n "$avd" ] && [ -n "$pid" ] || { echo "cannot find the AVD name ('$avd') or the emulator process ('$pid') of $serial (needs lsof)" >&2; exit 1; }
  echo "  SIGKILL of emulator process $pid (AVD $avd)"
  kill -9 "$pid"
  while kill -0 "$pid" 2>/dev/null; do sleep 0.2; done
  # shellcheck disable=SC2086
  nohup "${ANDROID_HOME:-$HOME/Library/Android/sdk}/emulator/emulator" -avd "$avd" -port "$port" -no-snapshot \
    ${EMULATOR_OPTS:--no-window -noaudio -no-boot-anim -gpu swiftshader_indirect} >/dev/null 2>&1 &
  local new_pid=$!
  for _ in $(seq 1 180); do  # 6 minutes; no wait-for-device (it has no timeout)
    kill -0 "$new_pid" 2>/dev/null || { echo "the restarted emulator process died" >&2; exit 1; }
    [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && adb shell pm path android >/dev/null 2>&1 && break
    sleep 2
  done
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] || { echo "the emulator did not boot again within 6 minutes" >&2; exit 1; }
  sleep 5
}

record() {
  [ -n "$2" ] || { echo "no value for $1" >&2; exit 1; }
  [ -z "${DURABILITY_NUMBERS:-}" ] || echo "$1=$2" >>"$DURABILITY_NUMBERS"
}

case_powerloss() {
  echo "== powerloss: checkpoint, more events, SIGKILL of the emulator process, cold boot, integrity and server checks"
  fresh_install
  local port=18098 log wlog
  log="$(mktemp)"; wlog="$(mktemp)"; tmp_files+=("$log" "$wlog")
  python3 scripts/ack-server.py "$port" "$log" &
  host_server_pid=$!
  sleep 1
  kill -0 "$host_server_pid" 2>/dev/null || { echo "host ACK server did not start (port $port in use?)" >&2; host_server_pid=""; exit 1; }
  local args=(-e pre "$PL_PRE" -e lostack "$PL_LOSTACK" -e pending "$PL_PENDING" -e tail "$PL_TAIL" -e endpoint "http://10.0.2.2:$port" -e skipCheckpoint "${POWERLOSS_SKIP_CHECKPOINT:-0}")
  powerloss powerloss_prepare "${args[@]}"
  adb shell sync # the installation and the empty queue are durable: only what powerloss_write writes can be lost
  adb shell am instrument -w -e class local.signaldock.device.PowerLossPhases -e phase powerloss_write "${args[@]}" "$RUNNER" >"$wlog" 2>&1 &
  writer_pid=$!
  local armed=0
  for _ in $(seq 1 1800); do  # 3 minutes
    if grep -q 'status=invalid' "$wlog"; then cat "$wlog" >&2; echo "invalid powerloss_write" >"$invalid_marker"; exit 1; fi
    # The instrumentation prints the keys sorted: dbSynchronous comes before status, so both are there when status is.
    if grep -q 'status=armed' "$wlog" && grep -q 'dbSynchronous=' "$wlog"; then armed=1; break; fi
    kill -0 "$writer_pid" 2>/dev/null || break
    sleep 0.1
  done
  [ "$armed" = 1 ] || { echo "powerloss_write did not report 'armed':" >&2; cat "$wlog" >&2; exit 1; }
  echo "  $(grep -o 'checkpoint=[^ ]* [^ ]* [^ ]*' "$wlog" | head -1)"
  local sync
  sync="$(sed -n 's/.*INSTRUMENTATION_STATUS: dbSynchronous=//p' "$wlog" | head -1 | tr -d '\r')"
  case "$sync" in
    2) echo "  synchronous=2 (FULL: every commit syncs; loss after the checkpoint is expected to be 0)" ;;
    1) echo "  synchronous=1 (NORMAL: commits after the checkpoint can be lost)" ;;
    *) echo "  synchronous=${sync:-?} (unexpected)" ;;
  esac
  record powerlossSynchronous "$sync"
  emulator_kill_and_cold_boot
  wait "$writer_pid" 2>/dev/null || true
  writer_pid=""
  powerloss powerloss_check "${args[@]}"
  kill "$host_server_pid" 2>/dev/null || true
  wait "$host_server_pid" 2>/dev/null || true
  host_server_pid=""
  local numbers
  numbers="$(python3 scripts/powerloss-verify.py "$log" "$PL_PRE" "$PL_LOSTACK" "$PL_PENDING" "$PL_TAIL")" || exit 1
  echo "  server: $(echo "$numbers" | tr '\n' ' ')(counted, not gated)"
  record lostAfterCheckpoint "$(sed -n 's/^lostAfterCheckpoint=//p' <<<"$numbers")"
  record resends "$(sed -n 's/^resends=//p' <<<"$numbers")"
}

# Gate A4 (ADR-0049 decision 2): the budget is the measured p99 plus a stated margin: 2 x the worst of 17 p99 values on CI
# emulators (API 24 and 37.0, runs 43692865454 to 43749875422): 84,401 us -> 170,000 us (ADR-0049 amendment 2026-11-15). Empty: not gated.
MAIN_THREAD_P99_BUDGET_US=170000

case_maincost() {
  echo "== maincost: 1,000 track() calls on the main thread, p99 against the budget (gate A4)"
  fresh_install
  phase_class=local.signaldock.device.MeasurePhases phase main_thread -e endpoint http://127.0.0.1:9 -e key device-test-key -e runId maincost
  local p99
  p99="$(get p99Micros)"
  echo "  p50 $(get p50Micros) us, p99 $p99 us, max $(get maxMicros) us over $(get calls) calls (budget: ${MAIN_THREAD_P99_BUDGET_US:-not set})"
  record mainThreadP99Micros "$p99"
  [ -z "$MAIN_THREAD_P99_BUDGET_US" ] || [ "$p99" -le "$MAIN_THREAD_P99_BUDGET_US" ] ||
    { echo "p99 $p99 us is above the budget $MAIN_THREAD_P99_BUDGET_US us" >&2; exit 1; }
}

for c in "${cases[@]}"; do
  known=0
  for k in "${all_cases[@]}"; do [ "$k" = "$c" ] && known=1; done
  [ "$known" = 1 ] || { echo "unknown case '$c'" >&2; exit 2; }
done
results=() failed=0
for c in "${cases[@]}"; do
  started="$(date +%s)"
  : >"$invalid_marker"
  set +e
  ( set -e; trap restore_device EXIT; "case_$c" )
  rc=$?
  set -e
  secs=$(( $(date +%s) - started ))
  if [ "$rc" = 0 ]; then result=pass
  elif [ -s "$invalid_marker" ]; then result=invalid; failed=1
  else result=fail; failed=1; fi
  echo "-> $c: $result (${secs} s)"
  results+=("{\"name\":\"$c\",\"result\":\"$result\",\"seconds\":$secs}")
done
if [ -n "${DURABILITY_RESULT_JSON:-}" ]; then
  ( IFS=,; echo "[${results[*]}]" ) >"$DURABILITY_RESULT_JSON"
fi
[ "$failed" = 0 ] || { echo "cases that did not pass: see above ($serial)" >&2; exit 1; }
echo "all requested cases passed on $serial"
