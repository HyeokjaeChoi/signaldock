#!/bin/bash
# What the SDK costs a host app (issue 36, ADR-0020): APK size, startup time, memory (PSS). Local only: not run in CI.
#
# Usage: scripts/host-cost-measure.sh <adb-serial>
#
# Builds the independent consumer app (compatibility/consumer-android, R8 release) twice from the Maven ZIP folder:
# with the SDK, and as a baseline with -Psignaldock.sdk=false (no dependency, no SDK call). The two APKs differ only by
# the SDK and what it brings. It then measures on one booted emulator (emulators only, no server needed):
#   - APK size: bytes, dex bytes, apkanalyzer download size; the libraries that come with the SDK
#   - startup: cold `am start -W` TotalTime, N runs per variant, interleaved; main-thread time in SignalDock.initialize()
#     (logged by the consumer app)
#   - PSS: `dumpsys meminfo` TOTAL PSS after startup plus an idle time, N runs per variant; and after a burst of track() calls
# and writes perf/sdk-results/host-cost-api<api>/ (CSV files, meta.json, summary.md). Emulator numbers, not device numbers.
#
# Needs: a booted emulator (for example `$ANDROID_HOME/emulator/emulator -avd signaldock-api37 -no-window -no-audio
# -no-snapshot-save -port 5584 &`), macOS, JDK 21 (scripts/setup-toolchain.sh), ANDROID_HOME (platform android-36,
# cmdline-tools with apkanalyzer), python3.
# Environment: HOSTCOST_RUNS (startup and idle PSS runs per variant, default 20), HOSTCOST_BURST_RUNS (default 10),
# HOSTCOST_BURST (track() calls per burst run, default 500), HOSTCOST_IDLE (seconds to wait before PSS, default 5).
set -euo pipefail

[ "$(uname)" = Darwin ] || { echo "error: this script reads macOS host facts (sysctl, sw_vers); run it on macOS" >&2; exit 1; }
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
serial="${1:?usage: scripts/host-cost-measure.sh <adb-serial>}"
case "$serial" in emulator-*) ;; *) echo "refusing $serial: emulators only" >&2; exit 1 ;; esac

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export JAVA_HOME="${JAVA_HOME:-$ROOT/.tools/jdk-21.0.12.1+1}"
[ -x "$JAVA_HOME/bin/java" ] || { echo "error: no JDK at $JAVA_HOME; run scripts/setup-toolchain.sh or set JAVA_HOME" >&2; exit 1; }
ADB="$ANDROID_HOME/platform-tools/adb"
APKANALYZER="$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer"
[ -x "$APKANALYZER" ] || { echo "error: no apkanalyzer at $APKANALYZER (install cmdline-tools)" >&2; exit 1; }
adb() { "$ADB" -s "$serial" "$@"; }
RUNS="${HOSTCOST_RUNS:-20}"
BURST_RUNS="${HOSTCOST_BURST_RUNS:-10}"
BURST="${HOSTCOST_BURST:-500}"
IDLE="${HOSTCOST_IDLE:-5}"
WARMUP=2  # launches per variant that are not counted (first start after install: dex verification, empty database)

"$ADB" -s "$serial" get-state >/dev/null 2>&1 || { echo "error: no emulator $serial" >&2; exit 1; }
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 2; done

VERSION="$(sed -n 's/^val sdkVersion = "\(.*\)"$/\1/p' sdk/sdk-android/build.gradle.kts)"
[ -n "$VERSION" ] || { echo "error: no sdkVersion in sdk/sdk-android/build.gradle.kts" >&2; exit 1; }
CONSUMER=compatibility/consumer-android
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
consumer() {
  # The endpoint is the discard port on the emulator itself: nothing answers, so no event leaves the device,
  # and a server that may run on the host never receives these events. The key is a dummy, not a secret: an empty
  # key fails configuration validation and initialize() would return at once.
  "$CONSUMER/gradlew" -p "$CONSUMER" -Psignaldock.repo="$WORK/repo" -Psignaldock.version="$VERSION" \
    -Psignaldock.endpoint=http://127.0.0.1:9 -Psignaldock.collectionKey=host-cost-dummy-key --no-daemon -q "$@"
}

echo "=== 1. Maven ZIP and the two consumer builds ==="
sdk/gradlew -p sdk :sdk-android:signaldockMavenZip --no-daemon -q
mkdir -p "$WORK/repo"
unzip -q "sdk/sdk-android/build/distributions/signaldock-sdk-android-$VERSION-maven.zip" -d "$WORK/repo"
for v in sdk nosdk; do
  mkdir -p "$WORK/$v"
  [ "$v" = nosdk ] && flag=false || flag=true
  consumer -Psignaldock.sdk=$flag :app:assembleRelease
  cp "$CONSUMER/app/build/outputs/apk/release/app-release.apk" "$WORK/$v/app-release.apk"
  cp "$CONSUMER/app/build/outputs/mapping/release/mapping.txt" "$WORK/$v/mapping.txt"
  consumer -Psignaldock.sdk=$flag :app:dependencies --configuration releaseRuntimeClasspath > "$WORK/$v/dependencies.txt"
done
PKG_sdk=local.signaldock.consumer
PKG_nosdk=local.signaldock.consumer.nosdk
ACT=local.signaldock.consumer.MainActivity
for v in sdk nosdk; do adb install -r "$WORK/$v/app-release.apk" >/dev/null; done

api="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
avd="$(adb emu avd name 2>/dev/null | head -1 | tr -d '\r')"
OUT="perf/sdk-results/host-cost-api$api"
mkdir -p "$WORK/out"
echo "device: $serial  AVD $avd  $(adb shell getprop ro.product.model | tr -d '\r')  API $api"

# pss <pkg>: TOTAL PSS in KiB from dumpsys meminfo.
pss() { adb shell dumpsys meminfo "$1" | tr -d '\r' | sed -n 's/^ *TOTAL PSS: *\([0-9]*\).*/\1/p' | head -1; }
# launch <pkg> [extra am args]: cold start; prints TotalTime in ms.
launch() {
  local pkg="$1"; shift
  adb shell am force-stop "$pkg"; sleep 1
  adb logcat -c
  adb shell am start -W -n "$pkg/$ACT" "$@" | tr -d '\r' | sed -n 's/^TotalTime: *\([0-9]*\).*/\1/p'
}

echo "=== 2. startup and idle PSS ($RUNS runs per variant, interleaved) ==="
echo "variant,run,total_time_ms,pss_idle_kib" > "$WORK/out/startup.csv"
echo "run,init_main_ns,init_total_ns" > "$WORK/out/init.csv"
for v in sdk nosdk; do
  pkg_var="PKG_$v"; pkg="${!pkg_var}"
  adb shell pm clear "$pkg" >/dev/null
  for _ in $(seq "$WARMUP"); do launch "$pkg" >/dev/null; sleep 2; done
done
for i in $(seq "$RUNS"); do
  if [ $((i % 2)) = 1 ]; then order="sdk nosdk"; else order="nosdk sdk"; fi
  for v in $order; do
    pkg_var="PKG_$v"; pkg="${!pkg_var}"
    t="$(launch "$pkg")"
    sleep "$IDLE"
    p="$(pss "$pkg")"
    [ -n "$t" ] && [ -n "$p" ] || { echo "error: no TotalTime or PSS for $v run $i" >&2; exit 1; }
    echo "$v,$i,$t,$p" >> "$WORK/out/startup.csv"
    if [ "$v" = sdk ]; then
      line="$(adb logcat -d -s SignalDockHostCost:I | tr -d '\r' | sed -n 's/.* init main_ns=\([0-9]*\) total_ns=\([0-9]*\) result=success.*/\1,\2/p' | head -1)"
      [ -n "$line" ] || { echo "error: no successful init log line in run $i" >&2; exit 1; }
      echo "$i,$line" >> "$WORK/out/init.csv"
    fi
  done
  echo "  run $i done"
done

echo "=== 3. PSS after $BURST track() calls ($BURST_RUNS runs per variant) ==="
echo "variant,run,pss_after_burst_kib" > "$WORK/out/pss-burst.csv"
for i in $(seq "$BURST_RUNS"); do
  if [ $((i % 2)) = 1 ]; then order="sdk nosdk"; else order="nosdk sdk"; fi
  for v in $order; do
    pkg_var="PKG_$v"; pkg="${!pkg_var}"
    launch "$pkg" --ei burst "$BURST" >/dev/null
    done_line=""
    for _ in $(seq 60); do
      done_line="$(adb logcat -d -s SignalDockHostCost:I | grep -E 'burst_(done|failed)' || true)"
      [ -n "$done_line" ] && break
      sleep 1
    done
    [ -n "$done_line" ] || { echo "error: burst did not finish for $v run $i" >&2; exit 1; }
    case "$done_line" in *burst_failed*) echo "error: track() failed during the burst for $v run $i: $done_line" >&2; exit 1 ;; esac
    sleep "$IDLE"
    p="$(pss "$pkg")"
    [ -n "$p" ] || { echo "error: no PSS for $v burst run $i" >&2; exit 1; }
    echo "$v,$i,$p" >> "$WORK/out/pss-burst.csv"
  done
  echo "  burst run $i done"
done

echo "=== 4. summary ==="
python3 - "$WORK" "$OUT" "$api" "$avd" "$serial" "$VERSION" "$RUNS" "$BURST_RUNS" "$BURST" "$IDLE" "$WARMUP" "$APKANALYZER" <<'EOF'
import csv, datetime, hashlib, json, math, os, re, shutil, statistics, subprocess, sys, xml.etree.ElementTree as ET, zipfile
work, out, api, avd, serial, version, runs, burst_runs, burst, idle, warmup, apkanalyzer = sys.argv[1:]
adb = os.path.join(os.environ["ANDROID_HOME"], "platform-tools", "adb")
def sh(*a): return subprocess.run(a, capture_output=True, text=True).stdout.strip()
def prop(k): return sh(adb, "-s", serial, "shell", "getprop", k)
def sha(p): return hashlib.sha256(open(p, "rb").read()).hexdigest()
def pct(xs, q):  # nearest rank
    xs = sorted(xs); return xs[max(math.ceil(q * len(xs)) - 1, 0)]
def stats(xs): return {"n": len(xs), "min": min(xs), "p50": statistics.median(xs), "p95": pct(xs, 0.95), "max": max(xs)}
def f(x): return f"{x:g}" if isinstance(x, (int, float)) else str(x)

os.makedirs(out, exist_ok=True)
for name in ("startup.csv", "init.csv", "pss-burst.csv"): shutil.copy(f"{work}/out/{name}", f"{out}/{name}")

# --- APK size
size = {}
for v in ("sdk", "nosdk"):
    apk = f"{work}/{v}/app-release.apk"
    z = zipfile.ZipFile(apk)
    dex = [i for i in z.infolist() if re.fullmatch(r"classes\d*\.dex", i.filename)]
    size[v] = {"apk_bytes": os.path.getsize(apk), "apk_download_bytes": int(sh(apkanalyzer, "apk", "download-size", apk)),
               "dex_files": len(dex), "dex_bytes": sum(i.file_size for i in dex), "dex_compressed_bytes": sum(i.compress_size for i in dex),
               "apk_sha256": sha(apk), "mapping_sha256": sha(f"{work}/{v}/mapping.txt"),
               "r8": next(l.split(": ")[1] for l in open(f"{work}/{v}/mapping.txt") if l.startswith("# compiler_version")).strip()}
with open(f"{out}/apk-size.csv", "w", newline="") as fh:
    w = csv.writer(fh); keys = ["apk_bytes", "apk_download_bytes", "dex_files", "dex_bytes", "dex_compressed_bytes"]
    w.writerow(["variant"] + keys)
    for v in ("nosdk", "sdk"): w.writerow([v] + [size[v][k] for k in keys])
    w.writerow(["delta"] + [size["sdk"][k] - size["nosdk"][k] for k in keys])

# --- dependencies: the SDK POM, and the libraries the SDK adds to the resolved runtime classpath
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
pom = ET.parse(f"{work}/repo/local/signaldock/sdk-android/{version}/sdk-android-{version}.pom").getroot()
pom_deps = [f"{d.findtext('m:groupId', namespaces=ns)}:{d.findtext('m:artifactId', namespaces=ns)}:{d.findtext('m:version', namespaces=ns)} ({d.findtext('m:scope', namespaces=ns)})"
            for d in pom.findall("m:dependencies/m:dependency", ns)]
def resolved(v):
    ids = set()
    for line in open(f"{work}/{v}/dependencies.txt"):
        m = re.search(r"[+\\]--- ([\w.\-]+:[\w.\-]+)(?::([\w.\-]+))?(?: -> ([\w.\-]+))?", line)
        if m and "project " not in line: ids.add(f"{m.group(1)}:{m.group(3) or m.group(2) or ''}")
    return ids
added = sorted(resolved("sdk") - resolved("nosdk"))
open(f"{out}/dependencies.txt", "w").write(
    f"POM of local.signaldock:sdk-android:{version} (direct runtime dependencies):\n" + "\n".join(pom_deps) +
    f"\n\nLibraries on the consumer releaseRuntimeClasspath with the SDK and not without it ({len(added)}):\n" + "\n".join(added) + "\n")

# --- startup, init, PSS
rows = list(csv.DictReader(open(f"{out}/startup.csv")))
col = lambda v, k: [int(r[k]) for r in rows if r["variant"] == v]
tt = {v: stats(col(v, "total_time_ms")) for v in ("nosdk", "sdk")}
pi = {v: stats(col(v, "pss_idle_kib")) for v in ("nosdk", "sdk")}
init = list(csv.DictReader(open(f"{out}/init.csv")))
im = stats([int(r["init_main_ns"]) / 1e6 for r in init]); it = stats([int(r["init_total_ns"]) / 1e6 for r in init])
brows = list(csv.DictReader(open(f"{out}/pss-burst.csv")))
pb = {v: stats([int(r["pss_after_burst_kib"]) for r in brows if r["variant"] == v]) for v in ("nosdk", "sdk")}
# paired deltas: run i of sdk against run i of nosdk (the two ran back to back)
def paired(rows, key):
    d = {}
    for r in rows: d.setdefault(r["run"], {})[r["variant"]] = int(r[key])
    return [x["sdk"] - x["nosdk"] for x in d.values()]
d_tt = paired(rows, "total_time_ms"); d_pi = paired(rows, "pss_idle_kib"); d_pb = paired(brows, "pss_after_burst_kib")

meta = {
    "issue": 36, "adr": "ADR-0020", "run_utc": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "avd": avd, "serial": serial, "api": int(api), "android_release": prop("ro.build.version.release"), "model": prop("ro.product.model"),
    "abi": prop("ro.product.cpu.abi"), "build_fingerprint": prop("ro.build.fingerprint"),
    "host": {"model": sh("sysctl", "-n", "machdep.cpu.brand_string"), "cpus": sh("sysctl", "-n", "hw.ncpu"), "os": sh("sw_vers", "-productVersion"),
             "loadavg_at_summary": sh("sysctl", "-n", "vm.loadavg")},
    "sdk_version": version, "git_revision": sh("git", "rev-parse", "--short", "HEAD") + (" (dirty)" if sh("git", "status", "--porcelain", "--untracked-files=no") else ""),
    "parameters": {"startup_runs_per_variant": int(runs), "burst_runs_per_variant": int(burst_runs), "burst_track_calls": int(burst),
                   "idle_seconds_before_pss": int(idle), "warmup_launches_per_variant_not_counted": int(warmup)},
    "variants": {"sdk": {"application_id": "local.signaldock.consumer", **size["sdk"]},
                 "nosdk": {"application_id": "local.signaldock.consumer.nosdk", **size["nosdk"]}},
}
json.dump(meta, open(f"{out}/meta.json", "w"), indent=2)

S, N = size["sdk"], size["nosdk"]
def pctd(a, b): return f"{(a - b) / b * 100:.1f} %"
def srow(name, s, unit): return f"| {name} | {f(s['nosdk']['p50'])} / {f(s['nosdk']['p95'])} / {f(s['nosdk']['max'])} | {f(s['sdk']['p50'])} / {f(s['sdk']['p95'])} / {f(s['sdk']['max'])} | {unit} |"
md = f"""# SDK cost for a host app (issue 36)

Measured on an emulator, not on a physical device: {avd} (API {api}, Android {meta['android_release']}, {meta['model']}, {meta['abi']}) on {meta['host']['model']}, one shared host (load average in `meta.json`). Numbers show the order of magnitude, not an SLA. Method and limits: ADR-0020.

Both APKs are the independent consumer app (`compatibility/consumer-android`), R8 release, built from the Maven ZIP of `local.signaldock:sdk-android:{version}`. The baseline is built with `-Psignaldock.sdk=false`: no SDK dependency, no SDK call, and no coroutines library. Both ran interleaved on the same emulator. Run {meta['run_utc']}, git {meta['git_revision']}.

## APK size (R8 release, R8 {N['r8']})

| | Without SDK | With SDK | Delta |
| --- | --- | --- | --- |
| APK bytes | {N['apk_bytes']} | {S['apk_bytes']} | +{S['apk_bytes'] - N['apk_bytes']} ({pctd(S['apk_bytes'], N['apk_bytes'])}) |
| Download size (apkanalyzer) | {N['apk_download_bytes']} | {S['apk_download_bytes']} | +{S['apk_download_bytes'] - N['apk_download_bytes']} ({pctd(S['apk_download_bytes'], N['apk_download_bytes'])}) |
| dex bytes ({N['dex_files']} / {S['dex_files']} files, sum of classes*.dex) | {N['dex_bytes']} | {S['dex_bytes']} | +{S['dex_bytes'] - N['dex_bytes']} |
| dex bytes in the APK (compressed) | {N['dex_compressed_bytes']} | {S['dex_compressed_bytes']} | +{S['dex_compressed_bytes'] - N['dex_compressed_bytes']} |

The baseline is a tiny app (two classes), so the percentage is large and says little; the byte delta is what an app adds. Resources are not shrunk (the consumer has no `shrinkResources`). Libraries that come with the SDK are in `dependencies.txt`: the POM lists {len(pom_deps)} direct dependencies, and {len(added)} libraries are new on the release runtime classpath (Room, WorkManager, OkHttp, Retrofit, kotlinx.serialization, coroutines and their dependencies).

## Startup ({runs} cold starts per variant, interleaved; {warmup} warm-up launches not counted)

| Metric (ms) | Without SDK p50 / p95 / max | With SDK p50 / p95 / max | Unit |
| --- | --- | --- | --- |
{srow('`am start -W` TotalTime', tt, 'ms')}

Paired difference of TotalTime (with minus without, run by run): median {f(statistics.median(d_tt))} ms, min {min(d_tt)}, max {max(d_tt)}.

Main-thread time in `SignalDock.initialize()` (consumer app, `SystemClock.elapsedRealtimeNanos()`, n={im['n']}): `initialize()` is called from `Application.onCreate` in a coroutine that starts undispatched on the main thread; the time until it first suspends is the main-thread cost (it includes the coroutine setup). The SDK does its work on its own IO scope.

| Metric (ms) | p50 | p95 | max |
| --- | --- | --- | --- |
| main thread until `initialize()` suspends | {im['p50']:.3f} | {im['p95']:.3f} | {im['max']:.3f} |
| until the init result is ready (wall, mostly off the main thread) | {it['p50']:.1f} | {it['p95']:.1f} | {it['max']:.1f} |

## Memory (PSS, `dumpsys meminfo` TOTAL PSS, KiB)

| Moment | Without SDK median (min - max) | With SDK median (min - max) | Delta median (paired min - max) |
| --- | --- | --- | --- |
| {idle} s after start (n={pi['sdk']['n']}) | {f(pi['nosdk']['p50'])} ({pi['nosdk']['min']} - {pi['nosdk']['max']}) | {f(pi['sdk']['p50'])} ({pi['sdk']['min']} - {pi['sdk']['max']}) | {f(statistics.median(d_pi))} ({min(d_pi)} to {max(d_pi)}) |
| {idle} s after {burst} track() calls (n={pb['sdk']['n']}) | {f(pb['nosdk']['p50'])} ({pb['nosdk']['min']} - {pb['nosdk']['max']}) | {f(pb['sdk']['p50'])} ({pb['sdk']['min']} - {pb['sdk']['max']}) | {f(statistics.median(d_pb))} ({min(d_pb)} to {max(d_pb)}) |

The endpoint was `http://127.0.0.1:9` (nothing answers), so the SDK queued events and its delivery attempts failed, as in an app that is offline. The baseline burst run logs and does nothing else. The min and max columns show the spread between runs.

Raw data: `apk-size.csv`, `startup.csv`, `init.csv`, `pss-burst.csv`, `dependencies.txt`, `meta.json`.
"""
open(f"{out}/summary.md", "w").write(md)
print(md)
EOF
echo "written $OUT"
